package sh.haven.core.tunnel

import sh.haven.core.data.db.entities.ConnectionProfile
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Single dispatch point for "how does this connection profile reach the
 * network?". Replaces ad-hoc `if (profile.tunnelConfigId != null) ...`
 * checks scattered across per-transport viewmodels.
 *
 * Three surfaces, each picked by the consumer based on what its transport
 * library can accept:
 *
 *  - [dial] — direct in-process [TunneledConnection]. Simplest path; for
 *    transports that own their socket creation in Kotlin (VNC, Reticulum).
 *  - [socketFactory] — javax.net.SocketFactory wrapping the same tunnel.
 *    For transports that take a factory (smbj's SmbConfig).
 *  - [socksEndpoint] — localhost SOCKS5 listener address. For transports
 *    behind FFI (rclone gomobile, IronRDP Rust) that can talk SOCKS5 but
 *    can't have their socket creation intercepted from Kotlin. Currently
 *    returns null; wired up when [Tunnel.socksAddress] (and the matching
 *    Go / tsnet listener) lands.
 *
 * All three return null when the profile has no tunnel selected — the
 * caller falls through to dialling a real Socket directly.
 *
 * Note: SOCKS / HTTP proxy fallback (the existing `proxyType`/`proxyHost`
 * /`proxyPort` columns on [ConnectionProfile]) is **not** consulted here
 * yet. Today those are read by `ConnectionsViewModel.createNetworkProxy`
 * for the SSH path only. Folding that path into TunnelResolver is part
 * of step 2 of the #149 rollout.
 */
@Singleton
class TunnelResolver @Inject constructor(
    private val tunnelManager: TunnelManager,
) {

    suspend fun dial(
        profile: ConnectionProfile,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): TunneledConnection? {
        val tunnel = tunnelFor(profile) ?: return null
        return tunnel.dial(host, port, timeoutMs)
    }

    suspend fun socketFactory(profile: ConnectionProfile): SocketFactory? {
        val tunnel = tunnelFor(profile) ?: return null
        return TunnelSocketFactory(tunnel)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun socksEndpoint(profile: ConnectionProfile): InetSocketAddress? {
        // Pending step 4 of #149 rollout — Tunnel.socksAddress() requires
        // the wgbridge / tsnet SOCKS5 listener. Until that lands,
        // FFI-bound transports (rclone, IronRDP) fall through to direct.
        return null
    }

    private suspend fun tunnelFor(profile: ConnectionProfile): Tunnel? {
        val tunnelId = profile.tunnelConfigId ?: return null
        return tunnelManager.getTunnel(tunnelId)
    }
}
