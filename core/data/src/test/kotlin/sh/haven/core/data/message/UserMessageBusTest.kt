package sh.haven.core.data.message

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the bus's semantics — `replay = 0` so a rotation doesn't re-show a
 * stale error; `tryEmit` never throws on overflow.
 */
class UserMessageBusTest {

    @Test
    fun `emit delivers to a subscribed collector`() = runTest(UnconfinedTestDispatcher()) {
        val bus = UserMessageBus()
        val received = mutableListOf<UserMessage>()
        val job = launch { bus.messages.collect { received.add(it) } }

        val msg = UserMessage("boom", UserMessage.Severity.ERROR)
        assertTrue(bus.emit(msg))

        assertEquals(listOf(msg), received)
        job.cancel()
    }

    @Test
    fun `error convenience emits an ERROR-severity message`() = runTest(UnconfinedTestDispatcher()) {
        val bus = UserMessageBus()
        val received = mutableListOf<UserMessage>()
        val job = launch { bus.messages.collect { received.add(it) } }

        bus.error("shell closed")

        assertEquals(1, received.size)
        assertEquals("shell closed", received[0].text)
        assertEquals(UserMessage.Severity.ERROR, received[0].severity)
        job.cancel()
    }

    @Test
    fun `late subscriber does not see prior messages (replay is zero)`() = runTest {
        val bus = UserMessageBus()
        bus.error("early — fired before anyone was listening")

        val received = mutableListOf<UserMessage>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            bus.messages.collect { received.add(it) }
        }

        assertTrue("late subscriber must not see prior messages, got: $received", received.isEmpty())
        job.cancel()
    }

    @Test
    fun `tryEmit never throws on overflow with no subscriber`() {
        val bus = UserMessageBus()
        // No collector: emit far past the buffer capacity; must not throw.
        repeat(100) { bus.emit(UserMessage("m$it")) }
    }

    @Test
    fun `ids are unique per message`() {
        val a = UserMessage("a")
        val b = UserMessage("b")
        assertTrue("message ids must differ", a.id != b.id)
    }
}
