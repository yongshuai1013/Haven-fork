package sh.haven.core.data.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConsentManagerTest {

    @Test
    fun `NEVER returns ALLOW without prompting`() = runTest {
        val mgr = AgentConsentManager()
        val decision = mgr.requestConsent(
            toolName = "list_connections",
            clientHint = "test",
            summary = "should not appear",
            level = ConsentLevel.NEVER,
        )
        assertEquals(ConsentDecision.ALLOW, decision)
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `armRetryWindow lets a subsequent call allow without a prompt`() = runTest {
        val mgr = AgentConsentManager()
        // Simulates the user tapping "Allow" on the backgrounded-consent
        // notification: the original call already DENIED (foreground=false),
        // and arming the window lets the agent's retry proceed — even while
        // still backgrounded — without re-prompting.
        mgr.armRetryWindow("agent-A", "read_logcat")
        val decision = mgr.requestConsent(
            toolName = "read_logcat",
            clientHint = "agent-A",
            summary = "retry",
            level = ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.ALLOW, decision)
    }

    @Test
    fun `armRetryWindow is scoped to the granted client and tool`() = runTest {
        val mgr = AgentConsentManager()
        mgr.armRetryWindow("agent-A", "read_logcat")
        // A different tool from the same client is NOT covered → fail-closed.
        val otherTool = mgr.requestConsent(
            "run_in_proot", "agent-A", "x", ConsentLevel.EVERY_CALL,
        )
        // The same tool from a different client is NOT covered → fail-closed.
        val otherClient = mgr.requestConsent(
            "read_logcat", "agent-B", "x", ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.DENY, otherTool)
        assertEquals(ConsentDecision.DENY, otherClient)
    }

    @Test
    fun `non-NEVER without foreground returns DENY immediately`() = runTest {
        val mgr = AgentConsentManager()
        // foregroundActive defaults to false — fail-closed, instant DENY.
        val decision = mgr.requestConsent(
            toolName = "delete_sftp_file",
            clientHint = "test",
            summary = "delete /tmp/x",
            level = ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.DENY, decision)
    }

    @Test
    fun `backgrounded fail-closed still instant-DENYs but emits a blocked-notification event`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = AgentConsentManager()
            val seen = mutableListOf<ConsentRequest>()
            val collector = launch { mgr.blockedWhileBackground.collect { seen.add(it) } }

            // foregroundActive=false → instant fail-closed DENY, AND a
            // notification event so the user knows what was blocked.
            val decision = mgr.requestConsent(
                toolName = "read_logcat",
                clientHint = "agent-A",
                summary = "read 400 logcat lines",
                level = ConsentLevel.EVERY_CALL,
            )

            assertEquals("fail-closed must instant-DENY", ConsentDecision.DENY, decision)
            assertEquals(1, seen.size)
            assertEquals("read_logcat", seen.single().toolName)
            assertEquals("agent-A", seen.single().clientHint)
            collector.cancel()
        }

    @Test
    fun `foregrounded request does not emit a blocked-notification event`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = AgentConsentManager()
            mgr.setForegroundActive(true)
            val seen = mutableListOf<ConsentRequest>()
            val collector = launch { mgr.blockedWhileBackground.collect { seen.add(it) } }

            val call = async {
                mgr.requestConsent(
                    toolName = "run_in_proot",
                    clientHint = "agent-A",
                    summary = "ip addr",
                    level = ConsentLevel.EVERY_CALL,
                    timeoutMs = Long.MAX_VALUE,
                )
            }
            // It queues a real prompt (no block event); approve it.
            mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
            assertEquals(ConsentDecision.ALLOW, call.await())
            assertTrue("no block event when foreground", seen.isEmpty())
            collector.cancel()
        }

    @Test
    fun `ONCE_PER_SESSION memoises ALLOW across calls`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        // First call: pending request appears, user taps allow.
        val first = async {
            mgr.requestConsent(
                toolName = "add_port_forward",
                clientHint = "agent-A",
                summary = "first",
                level = ConsentLevel.ONCE_PER_SESSION,
                timeoutMs = Long.MAX_VALUE,
            )
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        val pending = mgr.pending.value.single()
        mgr.respond(pending.id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, first.await())

        // Second call with same (client, tool) skips the prompt.
        val second = mgr.requestConsent(
            toolName = "add_port_forward",
            clientHint = "agent-A",
            summary = "second",
            level = ConsentLevel.ONCE_PER_SESSION,
        )
        assertEquals(ConsentDecision.ALLOW, second)
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `EVERY_CALL re-prompts even after a prior allow`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("delete_sftp_file", "agent-A", "first", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, first.await())

        // Second call must prompt again — verify pending is non-empty
        // before responding.
        val second = async {
            mgr.requestConsent("delete_sftp_file", "agent-A", "second", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, second.await())
    }

    @Test
    fun `DENY is never memoised for ONCE_PER_SESSION`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("add_port_forward", "agent-A", "first", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, first.await())

        // A misclick must not lock the agent out forever — second call
        // re-prompts.
        val second = async {
            mgr.requestConsent("add_port_forward", "agent-A", "second", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, second.await())
    }

    @Test
    fun `clearMemoised forces a re-prompt for ONCE_PER_SESSION`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("convert_file", "agent-A", "first", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        first.await()

        mgr.clearMemoised()

        val second = async {
            mgr.requestConsent("convert_file", "agent-A", "second", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        second.await()
    }

    @Test
    fun `bypass on Allow makes subsequent EVERY_CALL from same client skip the prompt`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("send_terminal_input", "claude-code", "send foo", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW, bypassClient = true)
        assertEquals(ConsentDecision.ALLOW, first.await())

        // Subsequent EVERY_CALL from the same client must short-circuit
        // — no prompt is queued, the call returns ALLOW immediately.
        val second = mgr.requestConsent(
            "delete_connection", "claude-code", "delete X", ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.ALLOW, second)
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `bypass is scoped per clientHint - a different client still prompts`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("any", "claude-code", "x", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW, bypassClient = true)
        first.await()

        // A different clientHint must still see a prompt — the bypass
        // is keyed per client, not global.
        val second = async {
            mgr.requestConsent("any", "rogue-app", "x", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        val req = mgr.pending.value.single()
        assertEquals("rogue-app", req.clientHint)
        mgr.respond(req.id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, second.await())
    }

    @Test
    fun `clearMemoised wipes the bypass set too`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val seed = async {
            mgr.requestConsent("write", "claude-code", "write", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW, bypassClient = true)
        seed.await()

        // Sanity: bypass is active — next call returns ALLOW without a prompt.
        assertEquals(
            ConsentDecision.ALLOW,
            mgr.requestConsent("write", "claude-code", "write", ConsentLevel.EVERY_CALL),
        )

        mgr.clearMemoised()

        // After clear, the same call must prompt again — DENY proves
        // the bypass really cleared and we're back through the queue.
        val afterClear = async {
            mgr.requestConsent("write", "claude-code", "write", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, afterClear.await())
    }

    @Test
    fun `bypassClient is ignored on DENY`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val first = async {
            mgr.requestConsent("write", "claude-code", "write", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // Setting bypassClient = true alongside DENY must NOT seed the
        // bypass set — otherwise a misclick on Deny would unlock the
        // client. Bypass only attaches to ALLOW.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY, bypassClient = true)
        assertEquals(ConsentDecision.DENY, first.await())

        val second = async {
            mgr.requestConsent("write", "claude-code", "write", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        // A prompt must land — proving the bypass didn't take.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        second.await()
    }

    @Test
    fun `requestClientPairing queues a _pairing request and resolves on response`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val pair = async {
            mgr.requestClientPairing("claude-code", "5.6.0", timeoutMs = Long.MAX_VALUE)
        }
        val req = mgr.pending.value.single()
        assertEquals(AgentConsentManager.PAIRING_TOOL_NAME, req.toolName)
        assertEquals("claude-code", req.clientHint)
        assertTrue("summary mentions the client name", req.summary.contains("claude-code"))
        assertTrue("summary mentions the version", req.summary.contains("5.6.0"))
        mgr.respond(req.id, ConsentDecision.ALLOW)
        assertEquals(ConsentDecision.ALLOW, pair.await())
    }

    @Test
    fun `requestClientPairing fails closed without a foreground activity`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        // foregroundActive defaults to false
        val decision = mgr.requestClientPairing("claude-code", null)
        assertEquals(ConsentDecision.DENY, decision)
        assertTrue(
            "no prompt should be queued when there's no UI to render it",
            mgr.pending.value.isEmpty(),
        )
    }

    @Test
    fun `requestClientPairing DENY does not seed a bypass`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        val pair = async { mgr.requestClientPairing("claude-code", null, timeoutMs = Long.MAX_VALUE) }
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, pair.await())

        // A subsequent per-call consent from the same client must still
        // hit the prompt path. If a DENY'd pairing had somehow added
        // the client to the bypass set, the next requestConsent would
        // return ALLOW with no prompt.
        val follow = async {
            mgr.requestConsent("send_terminal_input", "claude-code", "x", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        assertFalse(
            "pairing DENY must not have leaked into the bypass set",
            mgr.pending.value.isEmpty(),
        )
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        follow.await()
    }

    @Test
    fun `blocked pairing re-prompts on foreground and Pair arms the client's next initialize`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        // Client tries to pair while Haven is backgrounded → fail-closed.
        assertEquals(ConsentDecision.DENY, mgr.requestClientPairing("laptop", "1.0"))

        // User opens Haven (taps the notification) → the attempt re-prompts.
        mgr.setForegroundActive(true)
        val reprompt = async { mgr.repromptBlockedPairing() }
        val req = mgr.pending.value.single()
        assertEquals(AgentConsentManager.PAIRING_TOOL_NAME, req.toolName)
        assertEquals("laptop", req.clientHint)
        mgr.respond(req.id, ConsentDecision.ALLOW)
        assertEquals("laptop", reprompt.await())

        // The client's next initialize is covered by the armed window — even
        // if Haven has been backgrounded again by then — and prompts nothing.
        mgr.setForegroundActive(false)
        assertEquals(ConsentDecision.ALLOW, mgr.requestClientPairing("laptop", "1.0"))
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `repromptBlockedPairing is a no-op when nothing was blocked`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        assertEquals(null, mgr.repromptBlockedPairing())
        assertTrue(mgr.pending.value.isEmpty())
    }

    @Test
    fun `denying the pairing re-prompt does not arm the window`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        assertEquals(ConsentDecision.DENY, mgr.requestClientPairing("laptop", null))

        mgr.setForegroundActive(true)
        val reprompt = async { mgr.repromptBlockedPairing() }
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals("laptop", reprompt.await())

        // No window: a retry while backgrounded fails closed as before.
        mgr.setForegroundActive(false)
        assertEquals(ConsentDecision.DENY, mgr.requestClientPairing("laptop", null))
    }

    @Test
    fun `blocked pairing is single-slot - newest wins and it re-prompts once`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.requestClientPairing("first", null)
        mgr.requestClientPairing("second", null)

        mgr.setForegroundActive(true)
        val reprompt = async { mgr.repromptBlockedPairing() }
        assertEquals("second", mgr.pending.value.single().clientHint)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals("second", reprompt.await())

        // Consumed: a second foreground pass has nothing to show.
        assertEquals(null, mgr.repromptBlockedPairing())
    }

    @Test
    fun `memo is keyed on clientHint plus toolName`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)

        // Approve for client A.
        val a = async {
            mgr.requestConsent("add_port_forward", "agent-A", "a", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.ALLOW)
        a.await()

        // A different client must still be prompted — the memo key
        // includes the client hint so a compromised secondary agent
        // doesn't inherit a primary agent's blanket allow.
        val b = async {
            mgr.requestConsent("add_port_forward", "agent-B", "b", ConsentLevel.ONCE_PER_SESSION, timeoutMs = Long.MAX_VALUE)
        }
        // UnconfinedTestDispatcher runs the async block inline up to its
        // first suspension point — the deferred.await inside
        // requestConsent — so by the time control returns here, the
        // pending request is queued and ready to inspect.
        assertEquals(1, mgr.pending.value.size)
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        assertEquals(ConsentDecision.DENY, b.await())
    }

    @Test
    fun `persistent bypass auto-allows without prompting even for EVERY_CALL`() = runTest {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        mgr.setPersistentBypassClients(setOf("trusted-agent"))

        // No async/respond needed: a bypassed client short-circuits to
        // ALLOW before any prompt is queued, even for the strictest level.
        val decision = mgr.requestConsent(
            toolName = "delete_sftp_file",
            clientHint = "trusted-agent",
            summary = "delete /tmp/x",
            level = ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.ALLOW, decision)
        assertTrue("bypassed client must not queue a prompt", mgr.pending.value.isEmpty())
    }

    @Test
    fun `persistent bypass applies even with no foreground activity`() = runTest {
        val mgr = AgentConsentManager()
        // foregroundActive defaults to false — a non-bypassed client would
        // fail closed (DENY) here. The opt-in bypass deliberately outranks
        // the foreground gate, same as the session-bypass checkbox.
        mgr.setPersistentBypassClients(setOf("trusted-agent"))
        val decision = mgr.requestConsent(
            toolName = "delete_sftp_file",
            clientHint = "trusted-agent",
            summary = "delete /tmp/x",
            level = ConsentLevel.EVERY_CALL,
        )
        assertEquals(ConsentDecision.ALLOW, decision)
    }

    @Test
    fun `clearMemoised drops the persistent bypass snapshot`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = AgentConsentManager()
        mgr.setForegroundActive(true)
        mgr.setPersistentBypassClients(setOf("trusted-agent"))
        mgr.clearMemoised()

        // After a reset the client must prompt again rather than ride the
        // stale in-memory bypass snapshot.
        val follow = async {
            mgr.requestConsent("delete_sftp_file", "trusted-agent", "x", ConsentLevel.EVERY_CALL, timeoutMs = Long.MAX_VALUE)
        }
        assertFalse(
            "clearMemoised must drop the persistent bypass so a prompt is queued",
            mgr.pending.value.isEmpty(),
        )
        mgr.respond(mgr.pending.value.single().id, ConsentDecision.DENY)
        follow.await()
    }
}
