package sh.haven.core.mail

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MailSessionMgr"

/**
 * Tracks live mail sessions, mirroring `RcloneSessionManager` but far simpler:
 * Proton authenticates via SRP (no OAuth browser flow), so there is no logcat
 * URL monitor, worker pool, or timeout watcher. The Go bridge owns the actual
 * session; this holds the per-profile [SessionState] the UI observes.
 */
@Singleton
class MailSessionManager @Inject constructor(
    private val client: MailClient,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val errorMessage: String? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /** The injected engine, exposed for the feature-layer backend selector. */
    val mailClient: MailClient get() = client

    /** Background scope for fire-and-forget Proton session revokes on disconnect. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connected sessions — used by the registry's foreground keep-alive check. */
    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter { it.status == SessionState.Status.CONNECTED }

    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Log in and unlock the account for [sessionId], marking it CONNECTED on
     * success. On [MailException.TwoFaRequired] / [MailException.MailboxPasswordRequired]
     * the session is left CONNECTING and the exception rethrown so the caller can
     * re-prompt and retry with the same [sessionId]. Any other failure marks the
     * session ERROR (with the message) and rethrows.
     */
    suspend fun connectSession(
        sessionId: String,
        username: String,
        password: String,
        mailboxPassword: String? = null,
        twoFA: String? = null,
        socks: String? = null,
    ) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        try {
            client.login(
                sessionId = sessionId,
                username = username,
                password = password,
                mailboxPassword = mailboxPassword,
                twoFA = twoFA,
                socks = socks,
            )
            markConnected(sessionId)
        } catch (e: MailException.TwoFaRequired) {
            throw e // retryable — keep CONNECTING
        } catch (e: MailException.MailboxPasswordRequired) {
            throw e // retryable — keep CONNECTING
        } catch (e: Exception) {
            Log.w(TAG, "Mail login failed for session $sessionId", e)
            failSession(sessionId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun markConnected(sessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                errorMessage = null,
            ))
        }
    }

    private fun failSession(sessionId: String, message: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.ERROR,
                errorMessage = message,
            ))
        }
    }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    /** The opaque Go-bridge session id of [profileId]'s connected session, if any. */
    fun getSessionIdForProfile(profileId: String): String? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.sessionId

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun removeSession(sessionId: String) {
        _sessions.update { it - sessionId }
        revoke(listOf(sessionId))
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val ids = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.sessionId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        revoke(ids)
    }

    /**
     * Best-effort revoke the Proton session(s) in the Go bridge so a disconnect
     * actually tears down the server-side session and frees the bridge's
     * in-process registry entry — not just the local [SessionState]. Fire-and-
     * forget (a network call) on [scope]; the local map is already cleared.
     */
    private fun revoke(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        scope.launch {
            for (id in sessionIds) {
                runCatching { client.logout(id) }
                    .onFailure { Log.w(TAG, "logout($id) failed: ${it.message}") }
            }
        }
    }
}
