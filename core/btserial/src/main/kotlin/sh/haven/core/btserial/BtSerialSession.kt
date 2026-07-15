package sh.haven.core.btserial

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live Bluetooth-serial terminal session over an open [BtSerialTransport].
 *
 * A dedicated reader thread pumps RFCOMM input to the terminal callback;
 * keystrokes go back out via [sendInput]. Same transport-session shape as
 * EtSession / MoshSession, so [BtSerialSessionManager] mirrors the other
 * managers and the terminal ViewModel wires it identically (the callback IS
 * termlib's `feedOutput`).
 *
 * @param onDataReceived output sink — (buffer, offset, length), the emulator feed.
 * @param onDisconnected fired once when the link drops on its own (NOT on an
 *   explicit [close], which is a user teardown).
 */
class BtSerialSession(
    val sessionId: String,
    private val transport: BtSerialTransport,
    onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onDisconnected: (String) -> Unit,
) : Closeable {

    // Fetch the streams ONCE (some stream getters install a fresh pipe per call
    // and orphan buffered bytes — cf. the JSch #381 lesson).
    private val input = transport.input
    private val output = transport.output

    @Volatile
    private var callback: ((ByteArray, Int, Int) -> Unit)? = onDataReceived
    private val closed = AtomicBoolean(false)

    private val reader = Thread({
        val buf = ByteArray(4096)
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break // EOF — remote closed the link
                if (n > 0) callback?.invoke(buf, 0, n)
            }
        } catch (e: Exception) {
            if (!closed.get()) Log.d(TAG, "read loop ended for $sessionId: ${e.message}")
        } finally {
            // A self-terminating loop (EOF/error) reports the drop; an explicit
            // close() already set `closed`, so this no-ops there.
            if (closed.compareAndSet(false, true)) {
                runCatching { transport.close() }
                onDisconnected(sessionId)
            }
        }
    }, "btserial-read-$sessionId").apply { isDaemon = true }

    fun start() {
        reader.start()
    }

    /** Send keystrokes to the device. No-op once closed. */
    fun sendInput(bytes: ByteArray) {
        if (closed.get()) return
        try {
            output.write(bytes)
            output.flush()
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

    val remoteName: String? get() = transport.remoteName

    val isClosed: Boolean get() = closed.get()

    /** User teardown — does NOT fire [onDisconnected]. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            callback = null
            runCatching { transport.close() }
        }
    }

    companion object {
        private const val TAG = "BtSerialSession"
    }
}
