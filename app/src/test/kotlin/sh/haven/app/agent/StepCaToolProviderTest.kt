package sh.haven.app.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.StepCaConfig
import sh.haven.core.data.repository.StepCaConfigRepository

/**
 * Unit tests for the trusted-host-CA MCP verbs (#133/#380 follow-up): parsing,
 * validation, fingerprinting, and the CRUD contract against a mocked
 * [StepCaConfigRepository].
 */
class StepCaToolProviderTest {

    private val repo = mockk<StepCaConfigRepository>(relaxed = true)
    private val provider = StepCaToolProvider(repo)

    // ssh-keygen -t ed25519 test CA (public key line).
    private val caLine =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDQPF2NIer3s3pZq9jkhQ7QG4pTpphm haven-test-host-ca"

    private fun handler(name: String) = provider.tools().getValue(name)

    @Test
    fun `list surfaces only configs with a host CA key`() = runTest {
        coEvery { repo.getAll() } returns listOf(
            hostCaConfig("a", "trust-1", caLine),
            fullSigningConfig("b", "oidc-only"), // no host CA → omitted
            hostCaConfig("c", "trust-2", caLine),
        )
        val out = handler("list_trusted_host_cas").handle(org.json.JSONObject()).structured
        assertEquals(2, out.getInt("count"))
        val arr = out.getJSONArray("trustedHostCas")
        assertEquals(2, arr.length())
        assertEquals("trust-1", arr.getJSONObject(0).getString("name"))
        assertEquals("ssh-ed25519", arr.getJSONObject(0).getString("keyType"))
        assertTrue(arr.getJSONObject(0).getString("fingerprint").startsWith("SHA256:"))
    }

    @Test
    fun `add saves a host-CA-only config with blank signing fields`() = runTest {
        val saved = slot<StepCaConfig>()
        coEvery { repo.save(capture(saved)) } returns Unit
        val out = handler("add_trusted_host_ca").handle(
            org.json.JSONObject().put("name", "my-ca").put("caPublicKey", caLine),
        ).structured
        coVerify { repo.save(any()) }
        val cfg = saved.captured
        assertEquals("my-ca", cfg.name)
        assertEquals(caLine, cfg.sshHostCaPublicKey)
        // Host-CA-only: signing fields are blank, so the #380 UI path and this
        // verb produce the same shape.
        assertEquals("", cfg.caUrl)
        assertEquals("", cfg.oidcIssuer)
        assertEquals("", cfg.rootCertPem)
        assertEquals(cfg.id, out.getString("id"))
        assertTrue(out.getString("fingerprint").startsWith("SHA256:"))
    }

    @Test
    fun `add rejects a non-key value`() = runTest {
        val ex = runCatching {
            handler("add_trusted_host_ca").handle(
                org.json.JSONObject().put("name", "x").put("caPublicKey", "not a key !!!"),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
        coVerify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `add rejects a private key`() = runTest {
        val ex = runCatching {
            handler("add_trusted_host_ca").handle(
                org.json.JSONObject().put("name", "x")
                    .put("caPublicKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nabcd\n-----END OPENSSH PRIVATE KEY-----"),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `delete removes an existing config and reports removed true`() = runTest {
        coEvery { repo.getById("a") } returns hostCaConfig("a", "trust-1", caLine)
        val out = handler("delete_trusted_host_ca").handle(
            org.json.JSONObject().put("id", "a"),
        ).structured
        assertTrue(out.getBoolean("removed"))
        assertEquals("trust-1", out.getString("name"))
        coVerify { repo.delete("a") }
    }

    @Test
    fun `delete is a no-op for an unknown id`() = runTest {
        coEvery { repo.getById("ghost") } returns null
        val out = handler("delete_trusted_host_ca").handle(
            org.json.JSONObject().put("id", "ghost"),
        ).structured
        assertFalse(out.getBoolean("removed"))
        assertNull(out.opt("name"))
        coVerify(exactly = 0) { repo.delete(any()) }
    }

    @Test
    fun `both mutating verbs are consent-gated`() {
        assertEquals(
            sh.haven.core.data.agent.ConsentLevel.EVERY_CALL,
            handler("add_trusted_host_ca").consentLevel,
        )
        assertEquals(
            sh.haven.core.data.agent.ConsentLevel.EVERY_CALL,
            handler("delete_trusted_host_ca").consentLevel,
        )
        assertEquals(
            sh.haven.core.data.agent.ConsentLevel.NEVER,
            handler("list_trusted_host_cas").consentLevel,
        )
    }

    private fun hostCaConfig(id: String, name: String, ca: String) = StepCaConfig(
        id = id, name = name, caUrl = "", oidcIssuer = "", oidcAuthUrl = "",
        oidcTokenUrl = "", oidcClientId = "", provisioner = "", defaultPrincipals = "",
        rootCertPem = "", sshHostCaPublicKey = ca,
    )

    private fun fullSigningConfig(id: String, name: String) = StepCaConfig(
        id = id, name = name, caUrl = "https://ca", oidcIssuer = "https://i",
        oidcAuthUrl = "https://a", oidcTokenUrl = "https://t", oidcClientId = "cid",
        provisioner = "p", defaultPrincipals = "", rootCertPem = "-----BEGIN CERTIFICATE-----",
        sshHostCaPublicKey = null,
    )
}
