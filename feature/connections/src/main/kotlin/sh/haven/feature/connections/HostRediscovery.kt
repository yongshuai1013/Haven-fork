package sh.haven.feature.connections

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows a device across DHCP address changes by its SSH host key (#376).
 *
 * A profile's saved IP is just a hint — the verified host key is the device's
 * identity. When a connect fails with a network error on a private address,
 * [rediscover] sweeps the local /24 on the profile's port, keyscans the
 * responders, and — only when EXACTLY ONE machine presents the profile's
 * stored known-host key — persists the new address (and seeds the known-hosts
 * entry for it, so the next TOFU check matches instead of re-prompting for a
 * host we just identified BY that key). Ambiguity (two machines with the same
 * key, e.g. cloned VMs) fails closed.
 *
 * No stored key ⇒ no rediscovery: the key is the only identity we trust, so a
 * never-trusted profile can't be followed.
 */
@Singleton
class HostRediscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val knownHostDao: KnownHostDao,
    private val connectionRepository: ConnectionRepository,
) {
    /** Test seams — the real implementations dial sockets. */
    internal var probe: (host: String, port: Int, timeoutMs: Int) -> Boolean = ::probePort
    internal var keyScan: (host: String, port: Int) -> KnownHostEntry? =
        { h, p -> SshClient.keyScan(h, p) }
    internal var subnetBase: () -> String? = ::localSubnetBase

    /**
     * Returns the device's new address after persisting it, or null when
     * rediscovery doesn't apply (public/hostname address, no stored key, no
     * subnet) or finds no unambiguous match.
     */
    suspend fun rediscover(profile: ConnectionProfile): String? = withContext(Dispatchers.IO) {
        if (!isPrivateIpv4(profile.host)) return@withContext null
        val stored = knownHostDao.findByHostPort(profile.host, profile.port)
            ?: return@withContext null
        val base = subnetBase() ?: return@withContext null

        val candidates = coroutineScope {
            (1..254).map { i ->
                async {
                    val ip = "$base.$i"
                    ip.takeIf { it != profile.host && probe(it, profile.port, PROBE_TIMEOUT_MS) }
                }
            }.awaitAll().filterNotNull()
        }
        if (candidates.isEmpty()) return@withContext null
        Log.d(TAG, "rediscover ${profile.label}: ${candidates.size} responder(s) on :${profile.port}")

        val matches = coroutineScope {
            candidates.map { ip ->
                async {
                    val key = keyScan(ip, profile.port)
                    ip.takeIf {
                        key != null &&
                            key.keyType == stored.keyType &&
                            key.publicKeyBase64 == stored.publicKeyBase64
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val newHost = matches.singleOrNull() ?: run {
            Log.i(TAG, "rediscover ${profile.label}: ${matches.size} key match(es) — not following")
            return@withContext null
        }

        connectionRepository.updateHost(profile.id, newHost)
        knownHostDao.deleteByHostPort(newHost, profile.port)
        knownHostDao.upsert(stored.copy(id = 0, hostname = newHost))
        Log.i(TAG, "rediscover ${profile.label}: ${profile.host} → $newHost (host key matched)")
        newHost
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val p = host.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }

    // Same logic as NetworkDiscovery.getLocalSubnetBase/probePort — duplicated
    // (~20 lines) rather than widening that UI-lifecycle class's API.
    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE declared in app manifest
    private fun localSubnetBase(): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props: LinkProperties? = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        props?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress?.split(".")?.takeIf { it.size == 4 }
            ?.let { "${it[0]}.${it[1]}.${it[2]}" }
    } catch (e: Exception) {
        Log.e(TAG, "localSubnetBase failed", e)
        null
    }

    private fun probePort(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val TAG = "HostRediscovery"
        const val PROBE_TIMEOUT_MS = 400
    }
}
