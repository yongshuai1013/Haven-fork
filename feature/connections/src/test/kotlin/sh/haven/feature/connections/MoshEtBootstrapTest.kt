package sh.haven.feature.connections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SessionManager

/**
 * Pins the shared Mosh/ET bootstrap pieces extracted from the four
 * previously-duplicated connect blocks in ConnectionsViewModel: config
 * construction (interactive vs silent mode differences) and best-effort
 * session listing.
 */
class MoshEtBootstrapTest {

    private val profile = ConnectionProfile(
        id = "p1", label = "box", host = "example.com", port = 2222,
        username = "ian", connectionType = "SSH", forwardAgent = true,
    )
    private val auth = ConnectionConfig.AuthMethod.Password("pw")

    @Test fun silentDefaultsKeepProfileUsernameAndDefaultReconnectPolicy() {
        val config = moshEtBootstrapConfig(profile, auth, agentIdentities = emptyList())
        assertEquals("example.com", config.host)
        assertEquals(2222, config.port)
        assertEquals("ian", config.username)
        assertEquals(true, config.forwardAgent)
        // Silent connects historically omitted reconnectPolicy → class default.
        assertEquals(ConnectionConfig.ReconnectPolicy(), config.reconnectPolicy)
    }

    @Test fun interactiveOverridesUsernameAndHonoursProfileReconnectPolicy() {
        val p = profile.copy(autoReconnect = false, reconnectMaxAttempts = 9)
        val config = moshEtBootstrapConfig(
            p, auth, agentIdentities = emptyList(),
            username = "root", reconnectPolicy = p.reconnectPolicy,
        )
        assertEquals("root", config.username)
        assertEquals(
            ConnectionConfig.ReconnectPolicy(autoReconnect = false, maxAttempts = 9, onNetworkChange = p.reconnectOnNetworkChange),
            config.reconnectPolicy,
        )
    }

    // --- best-effort session listing ---

    private fun exec(status: Int, stdout: String): suspend (String) -> ExecResult =
        { ExecResult(exitStatus = status, stdout = stdout, stderr = "") }

    @Test fun parsesNamesOnlyForSuccessfulCommands() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX, exec(0, "main\nwork\n"))
        assertEquals(listOf("main", "work"), names)
    }

    @Test fun nonZeroExitYieldsEmpty() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX, exec(127, "tmux: not found"))
        assertTrue(names.isEmpty())
    }

    @Test fun execFailureYieldsEmpty() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX) {
            throw java.io.IOException("channel closed")
        }
        assertTrue(names.isEmpty())
    }

    @Test fun managerWithoutListCommandSkipsExecEntirely() = runBlocking {
        var called = false
        val names = listExistingMultiplexerSessions(SessionManager.NONE) {
            called = true
            ExecResult(0, "", "")
        }
        assertTrue(names.isEmpty())
        assertTrue(!called)
    }

    // --- auto-attach to the remembered multiplexer session (#371 follow-up) ---
    //
    // The transport can't be resumed after an app restart, but the tmux/zellij
    // session behind it can — so a reconnect goes straight back to it instead
    // of stopping at the picker. Null means "keep the picker".

    @Test fun remembersAndReattachesToTheSessionStillRunning() {
        val p = profile.copy(lastSessionName = "box")
        assertEquals("box", autoAttachSessionName(p, listOf("box", "scratch")))
    }

    @Test fun rememberedSessionGoneFallsBackToThePicker() {
        // Killed, or the host rebooted: the name no longer exists remotely.
        val p = profile.copy(lastSessionName = "box")
        assertNull(autoAttachSessionName(p, listOf("scratch", "other")))
    }

    @Test fun nothingRememberedKeepsThePicker() {
        assertNull(autoAttachSessionName(profile.copy(lastSessionName = null), listOf("scratch")))
        assertNull(autoAttachSessionName(profile.copy(lastSessionName = ""), listOf("scratch")))
    }

    @Test fun severalRememberedSessionsKeepThePicker() {
        // A multi-session restore is a real choice — don't make it silently.
        val p = profile.copy(lastSessionName = "box|scratch")
        assertNull(autoAttachSessionName(p, listOf("box", "scratch")))
    }

    @Test fun nothingRunningRemotelyStartsFresh() {
        // Remembered, but the remote has no sessions at all: null → the connect
        // falls through and creates the session (tmux new-session -A).
        val p = profile.copy(lastSessionName = "box")
        assertNull(autoAttachSessionName(p, emptyList()))
    }
}
