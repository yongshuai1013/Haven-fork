package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #mcp-backbone Stage 3 — pairing-token revocation at the persistence layer.
 *
 * A client's bearer credential is the SHA-256 hash stored in
 * [UserPreferencesRepository.mcpClientTokenHashes]; the MCP server authenticates
 * against a cache mirrored from that flow. So un-pairing MUST drop the client's
 * token hash from DataStore — if it only removed the allowlist entry, the stored
 * hash would keep authenticating the revoked client. This locks that drop (and
 * the sibling auto-approval revocation) so the server-side cache eviction it
 * feeds can't be silently defeated by an edit to `removeMcpAllowedClient`.
 */
@RunWith(RobolectricTestRunner::class)
class McpPairingPrefsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun unpairDropsTokenHashSoTheBearerStopsAuthenticating() = runBlocking {
        val repo = repo()
        repo.addMcpAllowedClient("alpha")
        repo.setMcpClientTokenHash("alpha", "hashA")
        repo.addMcpAllowedClient("beta")
        repo.setMcpClientTokenHash("beta", "hashB")
        assertEquals(setOf("alpha", "beta"), repo.mcpAllowedClients.first())
        assertEquals(mapOf("alpha" to "hashA", "beta" to "hashB"), repo.mcpClientTokenHashes.first())

        repo.removeMcpAllowedClient("alpha")

        // The regression this guards: alpha's token hash must be gone, not just
        // its allowlist entry. beta is untouched.
        assertNull("alpha's token hash survived the un-pair", repo.mcpClientTokenHashes.first()["alpha"])
        assertEquals(mapOf("beta" to "hashB"), repo.mcpClientTokenHashes.first())
        assertEquals(setOf("beta"), repo.mcpAllowedClients.first())
    }

    @Test
    fun unpairAlsoRevokesAutoApproval() = runBlocking {
        val repo = repo()
        repo.addMcpAllowedClient("alpha")
        repo.setMcpClientTokenHash("alpha", "hashA")
        repo.setMcpClientConsentBypass("alpha", enabled = true)
        assertTrue(repo.mcpBypassConsentClients.first().contains("alpha"))

        repo.removeMcpAllowedClient("alpha")

        assertFalse(
            "un-pairing must drop the standing auto-approval too",
            repo.mcpBypassConsentClients.first().contains("alpha"),
        )
    }

    @Test
    fun settingATokenHashForANameRotatesRatherThanDuplicates() = runBlocking {
        val repo = repo()
        repo.setMcpClientTokenHash("alpha", "old")
        repo.setMcpClientTokenHash("alpha", "new")
        // A stale second hash for the same name would mean the old token still
        // authenticates after a re-pair rotation.
        assertEquals(mapOf("alpha" to "new"), repo.mcpClientTokenHashes.first())
    }

    @Test
    fun clearAllWipesTokenHashesAndAllowlist() = runBlocking {
        val repo = repo()
        repo.addMcpAllowedClient("alpha")
        repo.setMcpClientTokenHash("alpha", "hashA")

        repo.clearMcpAllowedClients()

        assertTrue(repo.mcpAllowedClients.first().isEmpty())
        assertTrue(repo.mcpClientTokenHashes.first().isEmpty())
    }
}
