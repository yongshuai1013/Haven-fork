package sh.haven.feature.connections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.KnownHostEntry

/**
 * #376 — follow a device across DHCP address changes by SSH host key. Pins
 * the matching rules: exactly one key match follows (persist host + seed the
 * known-hosts row for the new address); ambiguity, wrong keys, no stored key,
 * and non-private addresses all fail closed with no writes.
 */
class HostRediscoveryTest {

    private val storedKey = KnownHost(
        id = 7, hostname = "192.168.43.5", port = 22,
        keyType = "ssh-ed25519", publicKeyBase64 = "AAAAtheRightKey",
        fingerprint = "SHA256:right", firstSeen = 1L,
    )
    private val profile = ConnectionProfile(
        id = "p1", label = "comma", host = "192.168.43.5", username = "comma",
    )

    private fun entry(ip: String, key: String, type: String = "ssh-ed25519") =
        KnownHostEntry(hostname = ip, port = 22, keyType = type, publicKeyBase64 = key)

    private fun rediscovery(
        dao: KnownHostDao,
        repo: ConnectionRepository = mockk(relaxed = true),
        responders: Set<String> = emptySet(),
        keys: Map<String, KnownHostEntry> = emptyMap(),
    ): HostRediscovery = HostRediscovery(
        context = mockk(relaxed = true),
        knownHostDao = dao,
        connectionRepository = repo,
    ).apply {
        subnetBase = { "192.168.43" }
        probe = { host, _, _ -> host in responders }
        keyScan = { host, _ -> keys[host] }
    }

    private fun daoWithStored(): KnownHostDao = mockk(relaxed = true) {
        coEvery { findByHostPort("192.168.43.5", 22) } returns storedKey
    }

    @Test
    fun `single key match persists host and seeds known-hosts row`() = runTest {
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("192.168.43.23", "192.168.43.1"),
            keys = mapOf(
                "192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey"),
                "192.168.43.1" to entry("192.168.43.1", "AAAArouterKey"),
            ),
        )

        assertEquals("192.168.43.23", r.rediscover(profile))
        coVerify(exactly = 1) { repo.updateHost("p1", "192.168.43.23") }
        coVerify(exactly = 1) {
            dao.upsert(match { it.hostname == "192.168.43.23" && it.publicKeyBase64 == "AAAAtheRightKey" })
        }
    }

    @Test
    fun `two machines with the same key is ambiguous - no follow`() = runTest {
        val dao = daoWithStored()
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(
            dao, repo,
            responders = setOf("192.168.43.23", "192.168.43.24"),
            keys = mapOf(
                "192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey"),
                "192.168.43.24" to entry("192.168.43.24", "AAAAtheRightKey"),
            ),
        )

        assertNull(r.rediscover(profile))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `no stored known-host key - no scan, no follow`() = runTest {
        val dao = mockk<KnownHostDao>(relaxed = true) {
            coEvery { findByHostPort(any(), any()) } returns null
        }
        val repo = mockk<ConnectionRepository>(relaxed = true)
        val r = rediscovery(dao, repo, responders = setOf("192.168.43.23"))
            .apply { subnetBase = { throw AssertionError("scan must not run without a stored key") } }

        assertNull(r.rediscover(profile))
        coVerify(exactly = 0) { repo.updateHost(any(), any()) }
    }

    @Test
    fun `key type mismatch does not match`() = runTest {
        val dao = daoWithStored()
        val r = rediscovery(
            dao,
            responders = setOf("192.168.43.23"),
            keys = mapOf("192.168.43.23" to entry("192.168.43.23", "AAAAtheRightKey", type = "ssh-rsa")),
        )

        assertNull(r.rediscover(profile))
    }

    @Test
    fun `public or hostname address is never rediscovered`() = runTest {
        val dao = daoWithStored()
        val r = rediscovery(dao)
        assertNull(r.rediscover(profile.copy(host = "example.com")))
        assertNull(r.rediscover(profile.copy(host = "8.8.8.8")))
    }
}
