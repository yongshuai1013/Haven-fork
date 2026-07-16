package sh.haven.app.agent

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live serial byte pipe the [SerialTcpBridge] taps. Each of the three serial
 * session types (BT-SPP / BLE-UART / USB-CDC) is adapted to this by the tool
 * provider — they already expose `sendInput` + `setTap`.
 */
interface SerialEndpoint {
    /** TCP → device: bytes a connected socket sent. */
    fun write(bytes: ByteArray)

    /**
     * device → TCP: register the sink that receives every inbound serial chunk,
     * or null to detach. The sink fires on the serial IO/reader thread and must
     * consume its buffer synchronously (BtSerialSession reuses one read buffer).
     */
    fun setTap(tap: ((ByteArray, Int, Int) -> Unit)?)
}

/**
 * Exposes one [SerialEndpoint] as a loopback TCP port so a serial device joins
 * the rest of Haven's fabric — an SSH remote-forward (add_port_forward) or a
 * tunnel can now carry it off-device. Raw bytes both ways, no framing: whatever
 * the peripheral emits reaches every connected client, and whatever any client
 * sends reaches the peripheral.
 *
 * ponytail: binds 127.0.0.1 only (never 0.0.0.0) — the port is a private tap for
 * the tunnel machinery, not a LAN service. Multiple clients are allowed and all
 * see the same output; a slow client can stall the serial reader because the tap
 * writes inline (acceptable for a hobby console — no per-client buffering yet).
 */
class SerialTcpBridge(
    private val endpoint: SerialEndpoint,
    requestedPort: Int, // 0 = let the OS pick a free port
) : Closeable {

    private val server = ServerSocket()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val closed = AtomicBoolean(false)

    /** The bound loopback port — feed this to add_port_forward / a tunnel. */
    val port: Int

    init {
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort))
        port = server.localPort
    }

    fun start() {
        endpoint.setTap { buf, off, len ->
            // Runs on the serial reader thread; write synchronously so a reused
            // read buffer isn't aliased across threads.
            for (client in clients) {
                try {
                    client.getOutputStream().apply { write(buf, off, len); flush() }
                } catch (_: IOException) {
                    dropClient(client)
                }
            }
        }
        Thread({ acceptLoop() }, "serial-tcp-bridge-$port").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break // server closed
            }
            clients.add(socket)
            Thread({ pumpClientToDevice(socket) }, "serial-tcp-client-$port")
                .apply { isDaemon = true }.start()
        }
    }

    private fun pumpClientToDevice(socket: Socket) {
        val buf = ByteArray(4096)
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) break // client closed
                if (n > 0) endpoint.write(buf.copyOfRange(0, n))
            }
        } catch (_: IOException) {
            // client vanished mid-read
        } finally {
            dropClient(socket)
        }
    }

    private fun dropClient(socket: Socket) {
        clients.remove(socket)
        runCatching { socket.close() }
    }

    /** Live connected-client count — surfaced by list_serial_bridges. */
    val clientCount: Int get() = clients.size

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            endpoint.setTap(null)
            runCatching { server.close() }
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }
}
