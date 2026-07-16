package sh.haven.core.bleserial

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live BLE-serial terminal session over an open [BleSerialLink].
 *
 * Same transport-session shape as [sh.haven.core.btserial.BtSerialSession] and
 * MoshSession/EtSession, so [BleSerialSessionManager] mirrors the other managers
 * and the terminal ViewModel wires it identically (the callback IS termlib's
 * `feedOutput`). Notification-driven rather than reader-thread based — the link
 * pushes bytes, so there is no blocking read loop.
 *
 * @param onDataReceived output sink — (buffer, offset, length), the emulator feed.
 * @param onDisconnected fired once when the link drops on its own (out of range,
 *   peripheral reset), NOT on an explicit [close].
 */
class BleSerialSession(
    val sessionId: String,
    private val link: BleSerialLink,
    onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: (String) -> Unit,
) : Closeable {

    @Volatile
    private var callback: ((ByteArray, Int, Int) -> Unit)? = onDataReceived

    /**
     * Optional read-only output tap (the serial↔TCP bridge). Fires alongside the
     * terminal [callback] on every inbound chunk; unlike [callback] it survives
     * [detach] (a bridge outlives a tab remount) and is dropped only by [setTap]
     * or [close]. Write to sinks synchronously — see SerialTcpBridge.
     */
    @Volatile
    private var tap: ((ByteArray, Int, Int) -> Unit)? = null
    private val closed = AtomicBoolean(false)

    fun setTap(t: ((ByteArray, Int, Int) -> Unit)?) { tap = t }

    fun start() {
        link.start(
            onData = { bytes ->
                callback?.invoke(bytes, 0, bytes.size)
                tap?.invoke(bytes, 0, bytes.size)
            },
            onError = {
                if (closed.compareAndSet(false, true)) {
                    runCatching { link.close() }
                    onDisconnected(sessionId)
                }
            },
        )
    }

    /** Send keystrokes to the peripheral. No-op once closed. */
    fun sendInput(bytes: ByteArray) {
        if (closed.get()) return
        try {
            link.write(bytes)
        } catch (e: Exception) {
            Log.d(TAG, "write failed for $sessionId: ${e.message}")
        }
    }

    /** Stop delivering output to the current terminal without dropping the link. */
    fun detach() {
        callback = null
    }

    /** Re-wire output to a (new) terminal after a tab remount. */
    fun reattach(onDataReceived: (ByteArray, Int, Int) -> Unit) {
        callback = onDataReceived
    }

    val remoteName: String? get() = link.displayName

    val isClosed: Boolean get() = closed.get()

    /** User teardown — does NOT fire [onDisconnected]. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            callback = null
            tap = null
            runCatching { link.close() }
        }
    }

    companion object {
        private const val TAG = "BleSerialSession"
    }
}
