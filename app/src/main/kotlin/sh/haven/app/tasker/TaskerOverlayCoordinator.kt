package sh.haven.app.tasker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Overlay path for the Tasker plugin (#367 Phase 2): run the command in a
 * VISIBLE terminal the user can watch, instead of the headless exec channel.
 *
 * Reuses a live session for the profile if one exists (just focus it);
 * otherwise emits [AgentUiCommand.ConnectProfile] — which navigates the UI to
 * a terminal tab — and waits for the session to come up. Then types the
 * command straight into the PTY.
 *
 * Delivery is a direct [SshSessionManager.sendInput], NOT the agent input
 * queue: the queue waits for *new* prompt output after a running command, but
 * a freshly-connected shell already sits at its prompt, so the queue would
 * wait forever. A short settle delay lets the login shell render its prompt,
 * and a brief retry covers the terminal tab attaching a beat after the session
 * reports CONNECTED.
 *
 * The caller (MainActivity, after the user taps the watch notification) has
 * already brought Haven to the foreground; this only drives connect + typing.
 */
@Singleton
class TaskerOverlayCoordinator @Inject constructor(
    private val agentUiCommandBus: AgentUiCommandBus,
    private val sshSessionManager: SshSessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun runInTerminal(profileId: String, command: String) {
        scope.launch {
            val existing = connectedSessionFor(profileId)
            if (existing != null) {
                agentUiCommandBus.emit(AgentUiCommand.FocusTerminalSession(existing))
                sendCommand(existing, command)
                return@launch
            }
            agentUiCommandBus.emit(AgentUiCommand.ConnectProfile(profileId))
            val sessionId = awaitConnectedSession(profileId, CONNECT_TIMEOUT_MS)
            if (sessionId == null) {
                Log.w(TAG, "no connected session for $profileId within timeout; command not sent")
                return@launch
            }
            delay(SETTLE_MS) // let a fresh login shell reach its prompt
            sendCommand(sessionId, command)
        }
    }

    private suspend fun sendCommand(sessionId: String, command: String) {
        val deadline = System.currentTimeMillis() + SEND_RETRY_MS
        while (System.currentTimeMillis() < deadline) {
            // sendInput throws until the terminal tab is attached — retry briefly.
            if (runCatching { sshSessionManager.sendInput(sessionId, command + "\n") }.isSuccess) {
                Log.i(TAG, "sent command to session $sessionId")
                return
            }
            delay(POLL_MS)
        }
        Log.w(TAG, "could not send command to session $sessionId (no terminal attached)")
    }

    private fun connectedSessionFor(profileId: String): String? =
        sshSessionManager.sessions.value.values
            .firstOrNull {
                it.profileId == profileId &&
                    it.status == SshSessionManager.SessionState.Status.CONNECTED
            }
            ?.sessionId

    private suspend fun awaitConnectedSession(profileId: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            connectedSessionFor(profileId)?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    companion object {
        private const val TAG = "TaskerOverlay"
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val SETTLE_MS = 1_200L
        private const val SEND_RETRY_MS = 5_000L
        private const val POLL_MS = 250L
    }
}
