package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream

/**
 * Blocking [InputStream] over a sshlib channel's `stdout`
 * ([ReceiveChannel] of ByteArray) (#58, phase 5).
 *
 * [sh.haven.core.ssh.TerminalSession] reads shell output with blocking
 * `read()` on a dedicated reader thread; sshlib delivers it as suspending
 * channel receives. This bridges the two: each `read()` blocks the calling
 * thread on the next chunk. End-of-stream (the channel closing when the
 * remote shell exits or the connection drops) surfaces as `read()` == -1,
 * exactly like JSch's channel InputStream.
 *
 * Not thread-safe: one reader thread only, matching TerminalSession's model.
 */
internal class ReceiveChannelInputStream(
    private val source: ReceiveChannel<ByteArray>,
) : InputStream() {

    private var buffer: ByteArray = EMPTY
    private var position: Int = 0
    private var eof: Boolean = false

    private fun ensureBuffered(): Boolean {
        while (position >= buffer.size) {
            if (eof) return false
            val chunk = runBlocking { source.receiveCatching().getOrNull() }
            if (chunk == null) {
                eof = true
                return false
            }
            if (chunk.isEmpty()) continue
            buffer = chunk
            position = 0
        }
        return true
    }

    override fun read(): Int {
        if (!ensureBuffered()) return -1
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensureBuffered()) return -1
        val n = minOf(len, buffer.size - position)
        System.arraycopy(buffer, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = buffer.size - position

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Blocking [OutputStream] that forwards keystrokes to a sshlib session's
 * suspending `write` (#58, phase 5). TerminalSession writes stdin from its
 * single write-executor thread; each `write` blocks that thread until the
 * suspend send completes.
 */
internal class SuspendWriteOutputStream(
    private val sink: suspend (ByteArray) -> Unit,
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val slice = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
        runBlocking { sink(slice) }
    }
}
