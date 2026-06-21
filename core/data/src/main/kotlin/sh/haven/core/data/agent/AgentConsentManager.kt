package sh.haven.core.data.agent

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How aggressively a tool gates a call on user consent.
 *
 * - [NEVER]              — read-only or otherwise harmless; no prompt.
 * - [ONCE_PER_SESSION]   — prompt once per (client, tool) pair, then
 *                          remember the decision until either the
 *                          session is cleared or the app is killed.
 * - [EVERY_CALL]         — prompt on every call. For the most
 *                          destructive operations.
 */
enum class ConsentLevel { NEVER, ONCE_PER_SESSION, EVERY_CALL }

/** Outcome the requesting RPC should act on. */
enum class ConsentDecision { ALLOW, DENY }

/**
 * One pending consent request the UI is expected to render and resolve.
 * `id` lets the UI route a tap on "Allow" / "Deny" back to the right
 * waiting RPC even when several pile up.
 */
data class ConsentRequest(
    val id: Long,
    val toolName: String,
    val clientHint: String?,
    /** Short human summary of what the tool will do, for the prompt body. */
    val summary: String,
    val requestedAt: Long = System.currentTimeMillis(),
    /**
     * If true, the consent sheet renders an additional "Allow for N min"
     * action alongside Allow/Deny. The ALLOW decision the user gives this
     * way is memoised against (clientHint, toolName) for that window —
     * subsequent identical requests within the window resolve
     * immediately as ALLOW without re-prompting. Used by the
     * [`queue_terminal_input`][sh.haven.app.agent.TerminalInputQueue]
     * delivery prompt where a power user wants to authorise the agent
     * to type into the REPL for a short collaborative window without
     * being interrupted on every queued message.
     */
    val offerTimedAllow: Boolean = false,
)

/**
 * Coordinates "agent wants to do something destructive — does the user
 * consent?" interactions. Designed but not yet wired: every tool in
 * MCP v1 is read-only, so there are no callers today. The shape needs
 * to exist before the first write tool lands so we don't have to
 * retrofit it under pressure.
 *
 * ### Failure model
 *
 * The brand promise (VISION.md §85) is that the user always keeps the
 * wheel — *never* a silent automation channel. When no Haven activity is
 * foreground there is nothing to render the prompt on, so the call cannot
 * proceed unattended. Rather than instant-denying (which the user never
 * sees), a non-NEVER request is **queued and a notification is raised**
 * ([blockedWhileBackground], rendered by the app layer) so the user can
 * foreground Haven and choose. The call still only proceeds on an explicit
 * ALLOW, and if the user never returns the queued request **times out to
 * DENY** — fail-closed in the limit, but no longer a silent instant-deny.
 * Foreground state is tracked via [setForegroundActive].
 *
 * ### Memoisation
 *
 * For [ConsentLevel.ONCE_PER_SESSION] decisions are cached against
 * the `(clientHint, toolName)` key for the lifetime of the manager
 * (i.e. until process death) or until [clearMemoised] is called from
 * Settings. The cache only stores ALLOW outcomes; a DENY is never
 * remembered, so a misclick can't lock the agent out forever.
 */
@Singleton
class AgentConsentManager @Inject constructor() {

    private val nextId = AtomicLong(1)
    private val mutex = Mutex()

    /**
     * Per-pending-request bundle: the deferred the caller is awaiting,
     * plus the `clientHint` carried with the request. The hint is needed
     * at respond-time so the bypass checkbox can attribute the bypass to
     * the right client. Replaces a plain `Map<Long, CompletableDeferred>`.
     */
    private data class PendingEntry(
        val deferred: CompletableDeferred<ConsentDecision>,
        val clientHint: String?,
    )

    private val pendingEntries = mutableMapOf<Long, PendingEntry>()
    private val sessionAllowed = mutableSetOf<String>()

    /**
     * Time-windowed memoisation: `memoKey -> expiry epoch millis`. A
     * user-supplied "Allow for N min" on a [ConsentRequest] with
     * `offerTimedAllow = true` writes here. [requestConsent] consults
     * this before queueing a fresh prompt and returns ALLOW
     * immediately if the entry hasn't expired. Cleared by
     * [clearMemoised] same as [sessionAllowed].
     */
    private val windowedAllows = mutableMapOf<String, Long>()

    /**
     * Single-use retry grants for EVERY_CALL tools: `grantKey -> expiry`. When
     * an EVERY_CALL operation is approved but the MCP client already gave up
     * (its read timeout < the consent wait), the approval would otherwise be
     * wasted and the agent's retry would re-prompt — the "unexpected MCP
     * timeout" symptom. Keyed by client+tool+operation (so a *different* call
     * still prompts) and CONSUMED on first use within a short TTL, so it never
     * weakens EVERY_CALL beyond honouring the just-approved operation's retry.
     */
    private val retryGrants = mutableMapOf<String, Long>()

    /**
     * Clients that the user has elected to bypass per-call prompts for
     * (the "Allow all MCP requests from 'X' until app restart" checkbox
     * on the consent sheet). Process-scoped, never persisted —
     * specifically cleared on app kill so a misclick can't survive an
     * unattended reboot. `clearMemoised()` also wipes this so Settings
     * → Forget remembered allows fully resets agent trust.
     */
    private val sessionBypassClients = mutableSetOf<String>()

    /**
     * Clients the user has *persistently* opted into auto-approval for,
     * via Settings → Agent endpoint → Paired clients. Unlike
     * [sessionBypassClients] this survives app restart: it's the
     * in-memory mirror of [UserPreferencesRepository.mcpBypassConsentClients],
     * pushed in through [setPersistentBypassClients] by the app layer
     * (same external-state-via-setter pattern as [setForegroundActive],
     * keeping this class free of any DataStore dependency). A tool call
     * from a name in this set bypasses the per-call consent prompt.
     */
    @Volatile
    private var persistentBypassClients: Set<String> = emptySet()

    @Volatile
    private var foregroundActive: Boolean = false

    private val _pending = MutableStateFlow<List<ConsentRequest>>(emptyList())
    /** All currently-waiting requests, oldest first. Drives the bottom sheet. */
    val pending: StateFlow<List<ConsentRequest>> = _pending.asStateFlow()

    private val _blockedWhileBackground = MutableSharedFlow<ConsentRequest>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Emits whenever a non-[NEVER] request had to fail closed purely because
     * no Haven activity was foreground to render the prompt (see [requestConsent]
     * / [requestClientPairing]). The app layer turns this into a heads-up,
     * tap-to-open notification so the user knows the agent is waiting on them —
     * the §85 design note's "notification explaining what was blocked".
     *
     * Fail-closed is unchanged: the request still returns DENY. This is purely
     * a "come back and you'll be asked" nudge, never a back-door approval.
     */
    val blockedWhileBackground: SharedFlow<ConsentRequest> = _blockedWhileBackground.asSharedFlow()

    /**
     * Activity layer reports its visibility through this so we can
     * fail-closed when no one can answer the prompt.
     */
    fun setForegroundActive(active: Boolean) {
        foregroundActive = active
    }

    /**
     * Replace the set of persistently auto-approved clients. Called by
     * the app layer whenever [UserPreferencesRepository.mcpBypassConsentClients]
     * changes (collected in `HavenApp`). Idempotent; a plain volatile
     * write — the read path in [requestConsent] only needs a consistent
     * snapshot, not a lock.
     */
    fun setPersistentBypassClients(clients: Set<String>) {
        persistentBypassClients = clients
    }

    /**
     * Suspend until the user resolves the consent prompt for [toolName],
     * or [timeoutMs] elapses. Returns DENY on timeout, on a backgrounded
     * app, or on an explicit deny tap. Returns ALLOW if memoised at
     * [ConsentLevel.ONCE_PER_SESSION] or if the user taps allow.
     *
     * [level] of [ConsentLevel.NEVER] always returns ALLOW immediately
     * without touching the queue — the parameter exists so callers can
     * thread the level uniformly through the dispatcher.
     */
    suspend fun requestConsent(
        toolName: String,
        clientHint: String?,
        summary: String,
        level: ConsentLevel,
        timeoutMs: Long = 60_000,
        offerTimedAllow: Boolean = false,
        operationKey: String? = null,
    ): ConsentDecision {
        if (level == ConsentLevel.NEVER) return ConsentDecision.ALLOW

        // Bypass: if the user has opted this client out of per-call
        // prompts — either persistently (Settings → Paired clients) or
        // for the session ("Allow all MCP requests from '<client>' until
        // app restart" checkbox) — skip prompts for any non-NEVER tool
        // from that client. Applies to EVERY_CALL too: that's the
        // "ill-advised" part the UX copy warns about. The persistent set
        // is a volatile snapshot fed from prefs; the session set is mutex
        // -guarded because the respond() path mutates it concurrently.
        if (clientHint != null) {
            if (clientHint in persistentBypassClients) return ConsentDecision.ALLOW
            mutex.withLock {
                if (clientHint in sessionBypassClients) return ConsentDecision.ALLOW
            }
        }

        val memoKey = memoKey(clientHint, toolName)
        if (level == ConsentLevel.ONCE_PER_SESSION) {
            mutex.withLock {
                if (memoKey in sessionAllowed) return ConsentDecision.ALLOW
            }
        }
        // Time-windowed memo (set by a previous "Allow for N min"
        // response). Checked for *all* levels because the timed allow
        // explicitly outranks the per-call gate within its window.
        mutex.withLock {
            val expiry = windowedAllows[memoKey]
            if (expiry != null) {
                if (expiry > System.currentTimeMillis()) return ConsentDecision.ALLOW
                windowedAllows.remove(memoKey) // expired — clean up opportunistically
            }
        }
        // Single-use retry grant for EVERY_CALL: honour a just-approved
        // operation whose client timed out before the result arrived, so the
        // agent's retry of the SAME operation proceeds instead of re-prompting.
        if (level == ConsentLevel.EVERY_CALL) {
            val grantKey = "$memoKey ${operationKey.orEmpty()}"
            mutex.withLock {
                val expiry = retryGrants.remove(grantKey) // consume regardless
                if (expiry != null && expiry > System.currentTimeMillis()) {
                    return ConsentDecision.ALLOW
                }
            }
        }

        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<ConsentDecision>()
        val request = ConsentRequest(
            id = id,
            toolName = toolName,
            clientHint = clientHint,
            summary = summary,
            offerTimedAllow = offerTimedAllow,
        )

        // No activity is foreground to render the sheet right now. Rather than
        // silently instant-denying (which the user never sees), queue the
        // request and raise a notification (rendered by the app layer) so the
        // user can foreground Haven and choose. §85 still holds: the call only
        // proceeds on an explicit ALLOW, and if the user never returns it
        // times out to DENY at the await below — fail-closed in the limit, but
        // no longer a silent instant-deny.
        if (!foregroundActive) {
            _blockedWhileBackground.tryEmit(request)
        }

        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientHint)
            _pending.value = _pending.value + request
        }

        // The caller (McpServer) wraps this in its own withTimeoutOrNull, so a
        // cancellation can hit us while suspended on the await below. The
        // pending-entry teardown MUST run anyway — otherwise the request is
        // orphaned in `_pending`, the consent sheet is never dismissed, and its
        // buttons resolve a dead deferred (the "UI frozen after a consent
        // timeout" bug). Hence finally + NonCancellable.
        val decision = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConsentDecision.DENY
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pendingEntries.remove(id)
                    _pending.value = _pending.value.filterNot { it.id == id }
                }
            }
        }

        if (decision == ConsentDecision.ALLOW) {
            when (level) {
                ConsentLevel.ONCE_PER_SESSION -> mutex.withLock { sessionAllowed.add(memoKey) }
                // Arm a single-use retry grant so the approval survives a client
                // that timed out mid-wait (consumed on the retry; see retryGrants).
                ConsentLevel.EVERY_CALL -> {
                    val grantKey = "$memoKey ${operationKey.orEmpty()}"
                    mutex.withLock {
                        retryGrants[grantKey] = System.currentTimeMillis() + RETRY_GRANT_MS
                    }
                }
                else -> Unit
            }
        }
        return decision
    }

    /**
     * Suspend until the user resolves a pairing prompt for a new MCP
     * client, or [timeoutMs] elapses. Distinct from [requestConsent] —
     * a paired client is a *connection-time* gate (in `initialize`),
     * not a *per-tool* gate. Returns DENY on timeout / background.
     *
     * The caller (McpServer) is responsible for persisting the client
     * name on ALLOW; this manager doesn't touch DataStore.
     */
    suspend fun requestClientPairing(
        clientName: String,
        clientVersion: String?,
        timeoutMs: Long = 60_000,
    ): ConsentDecision {
        val id = nextId.getAndIncrement()
        Log.i(LOG_TAG, "requestClientPairing('$clientName' v${clientVersion ?: "?"}): queueing prompt id=$id")
        val deferred = CompletableDeferred<ConsentDecision>()
        val versionSuffix = clientVersion?.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""
        val request = ConsentRequest(
            id = id,
            toolName = PAIRING_TOOL_NAME,
            clientHint = clientName,
            summary = "MCP client '$clientName'$versionSuffix wants to connect to Haven. " +
                "Once paired, it will be able to call tools — each tool still asks for " +
                "consent unless you elect to bypass that with the per-call checkbox.",
        )

        // Backgrounded: queue + notify instead of instant-deny, so the user
        // can foreground Haven and decide (see requestConsent). Times out to
        // DENY if they never return.
        if (!foregroundActive) {
            Log.w(LOG_TAG, "requestClientPairing('$clientName'): foreground=false — notifying + queueing")
            _blockedWhileBackground.tryEmit(request)
        }

        mutex.withLock {
            pendingEntries[id] = PendingEntry(deferred, clientName)
            _pending.value = _pending.value + request
        }

        // finally + NonCancellable: see requestConsent — an outer cancellation
        // must not orphan the pending entry / leave the sheet stuck on screen.
        val raw = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pendingEntries.remove(id)
                    _pending.value = _pending.value.filterNot { it.id == id }
                }
            }
        }
        val decision = raw ?: ConsentDecision.DENY
        Log.i(LOG_TAG, "requestClientPairing('$clientName') id=$id resolved: ${if (raw == null) "TIMEOUT->DENY" else decision.toString()}")
        return decision
    }

    /**
     * Called by the bottom-sheet UI when the user taps Allow / Deny.
     * When [bypassClient] is true and the decision is ALLOW, the
     * pending request's clientHint is added to [sessionBypassClients]
     * so subsequent per-tool prompts from that client short-circuit
     * to ALLOW. The flag is ignored on DENY or when the request had no
     * clientHint (e.g. an `_pairing` request — bypass on pairing is a
     * contradiction the UI prevents at render time).
     */
    suspend fun respond(
        requestId: Long,
        decision: ConsentDecision,
        bypassClient: Boolean = false,
        allowForMinutes: Int? = null,
    ) {
        mutex.withLock {
            val entry = pendingEntries[requestId] ?: return
            if (decision == ConsentDecision.ALLOW && bypassClient && entry.clientHint != null) {
                sessionBypassClients.add(entry.clientHint)
            }
            if (decision == ConsentDecision.ALLOW && allowForMinutes != null && allowForMinutes > 0) {
                // Set/refresh the windowed-allow expiry for this
                // (client, tool) pair. Future requests within the
                // window short-circuit to ALLOW via [requestConsent]
                // above without re-prompting.
                val pendingRequest = _pending.value.firstOrNull { it.id == requestId }
                val toolName = pendingRequest?.toolName
                if (toolName != null) {
                    val key = memoKey(entry.clientHint, toolName)
                    windowedAllows[key] = System.currentTimeMillis() + allowForMinutes * 60_000L
                }
            }
            entry.deferred.complete(decision)
        }
    }

    /**
     * Used by Settings → "Forget remembered allows". Clears the session
     * caches here; the *persistent* bypass set lives in DataStore, so the
     * caller (`SettingsViewModel`) also wipes
     * [UserPreferencesRepository.mcpBypassConsentClients] — that change
     * then flows back here via [setPersistentBypassClients]. We also drop
     * the in-memory persistent snapshot immediately so the reset takes
     * effect without waiting for the collector to re-emit.
     */
    suspend fun clearMemoised() {
        persistentBypassClients = emptySet()
        mutex.withLock {
            sessionAllowed.clear()
            sessionBypassClients.clear()
            windowedAllows.clear()
        }
    }

    private fun memoKey(clientHint: String?, toolName: String): String =
        "${clientHint ?: "unknown"}::$toolName"

    companion object {
        /**
         * Sentinel tool name carried by client-pairing prompts so the UI
         * can render a "Pair MCP client?" header instead of an
         * "Agent action requested" one, and so the bypass checkbox can
         * be suppressed on the pairing prompt itself (the user isn't
         * agreeing to specific calls yet — they're agreeing to let this
         * client even speak).
         */
        const val PAIRING_TOOL_NAME = "_pairing"

        private const val LOG_TAG = "AgentConsent"

        /**
         * How long a just-approved EVERY_CALL operation's single-use retry grant
         * stays valid (long enough for the agent to retry after a client-side
         * timeout, short enough not to span unrelated activity).
         */
        private const val RETRY_GRANT_MS = 45_000L
    }
}
