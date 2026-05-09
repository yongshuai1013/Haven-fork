package sh.haven.core.tunnel

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * java.net.Socket adapter backed by a [TunneledConnection].
 *
 * Userspace tunnels (WireGuard / Tailscale) don't expose kernel sockets;
 * a [TunneledConnection] is the byte-stream surface they offer instead.
 * Some upstream libraries hard-code a Socket dependency though — VNC's
 * VeNCrypt upgrade calls `SSLSocketFactory.createSocket(socket, host,
 * port, true)` (the layered-SSL form) and smbj's SmbConfig takes a
 * javax.net.SocketFactory whose contract returns Sockets. This class
 * bridges both: hand a TunneledSocket to those APIs and they read/write
 * via the underlying TunneledConnection.
 *
 * The connection is pre-dialled — [connect] and [bind] therefore throw
 * [UnsupportedOperationException]. [close] is idempotent.
 */
class TunneledSocket(
    private val connection: TunneledConnection,
    private val host: String,
    private val port: Int,
) : Socket() {

    @Volatile
    private var closed: Boolean = false

    override fun getInputStream(): InputStream {
        if (closed) throw IOException("Socket is closed")
        return connection.inputStream
    }

    override fun getOutputStream(): OutputStream {
        if (closed) throw IOException("Socket is closed")
        return connection.outputStream
    }

    override fun getInetAddress(): InetAddress? {
        return try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    override fun getPort(): Int = port

    override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()

    override fun getLocalPort(): Int = -1

    override fun isConnected(): Boolean = !closed

    override fun isBound(): Boolean = false

    override fun isClosed(): Boolean = closed

    override fun isInputShutdown(): Boolean = closed

    override fun isOutputShutdown(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.close()
        } catch (_: Throwable) {
            // best-effort teardown
        }
    }

    override fun connect(endpoint: SocketAddress?) {
        throw UnsupportedOperationException("TunneledSocket is pre-connected")
    }

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        throw UnsupportedOperationException("TunneledSocket is pre-connected")
    }

    override fun bind(bindpoint: SocketAddress?) {
        throw UnsupportedOperationException("TunneledSocket cannot be bound")
    }

    override fun shutdownInput() {
        // No-op — userspace tunnels don't expose half-close.
    }

    override fun shutdownOutput() {
        // No-op — userspace tunnels don't expose half-close.
    }
}
