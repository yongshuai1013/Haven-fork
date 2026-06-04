package sh.haven.core.data.message

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-global channel for transient, user-facing messages (errors, warnings,
 * info) that must be shown **regardless of which screen is visible**.
 *
 * The motivating case (#215 follow-up): an SSH connect whose remote shell
 * closes immediately used to set a `_error` StateFlow whose snackbar host was
 * scoped to the Connections screen — so once the connect optimistically
 * navigated to Terminal, the error was never seen. Any ViewModel can
 * [emit] here and a single host in `HavenNavHost` (collected above the pager)
 * renders it over every screen.
 *
 * Modeled on [sh.haven.core.data.agent.AgentUiCommandBus]: `replay = 0` so a
 * rotation doesn't re-show a stale message (these are events, not state), and
 * `tryEmit` never suspends. A slightly larger buffer than the command bus
 * because connect errors can burst (e.g. a workspace group launch).
 */
@Singleton
class UserMessageBus @Inject constructor() {
    private val _messages = MutableSharedFlow<UserMessage>(
        extraBufferCapacity = 8,
        replay = 0,
    )
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    /** Returns true when the message was buffered/delivered, false on overflow. */
    fun emit(message: UserMessage): Boolean = _messages.tryEmit(message)

    /** Convenience: emit an error-severity message. */
    fun error(text: String): Boolean = emit(UserMessage(text, UserMessage.Severity.ERROR))
}

/**
 * A transient message to surface to the user. [id] is a process-monotonic
 * value so a host can key/dedupe without comparing text.
 */
data class UserMessage(
    val text: String,
    val severity: Severity = Severity.ERROR,
    val id: Long = nextId(),
) {
    enum class Severity { ERROR, WARNING, INFO }

    companion object {
        private val counter = AtomicLong(0)
        private fun nextId(): Long = counter.incrementAndGet()
    }
}
