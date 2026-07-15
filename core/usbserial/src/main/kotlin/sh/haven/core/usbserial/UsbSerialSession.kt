package sh.haven.core.usbserial

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live USB-serial terminal session over an open [UsbSerialLink].
 *
 * Same transport-session shape as BtSerialSession / EtSession / MoshSession, so
 * [UsbSerialSessionManager] mirrors the other managers and the terminal
 * ViewModel wires it identically (the callback IS termlib's `feedOutput`). The
 * read loop lives inside the link (usb-serial-for-android's IO manager thread);
 * this class just routes its bytes to the terminal and guards teardown.
 *
 * @param onDataReceived output sink — (buffer, offset, length), the emulator feed.
 * @param onDisconnected fired once when the link drops on its own (NOT on an
 *   explicit [close], which is a user teardown).
 */
class UsbSerialSession(
    val sessionId: String,
    private val link: UsbSerialLink,
    onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: (String) -> Unit,
) : Closeable {

    @Volatile
    private var callback: ((ByteArray, Int, Int) -> Unit)? = onDataReceived
    private val closed = AtomicBoolean(false)

    fun start() {
        link.start(
            onData = { buf -> if (!closed.get()) callback?.invoke(buf, 0, buf.size) },
            onError = { e ->
                // The link died on its own (cable pulled, adapter error). Report
                // the drop once; an explicit close() already set `closed`, so this
                // no-ops there.
                if (!closed.get()) Log.d(TAG, "read loop ended for $sessionId: ${e.message}")
                if (closed.compareAndSet(false, true)) {
                    runCatching { link.close() }
                    onDisconnected(sessionId)
                }
            },
        )
    }

    /** Send keystrokes / commands to the device. No-op once closed. */
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

    val displayName: String? get() = link.displayName

    val isClosed: Boolean get() = closed.get()

    /** User teardown — does NOT fire [onDisconnected]. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            callback = null
            runCatching { link.close() }
        }
    }

    companion object {
        private const val TAG = "UsbSerialSession"
    }
}
