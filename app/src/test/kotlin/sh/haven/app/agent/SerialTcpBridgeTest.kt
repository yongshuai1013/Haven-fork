package sh.haven.app.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives [SerialTcpBridge] over a real loopback socket with a fake endpoint —
 * no serial hardware. Asserts both directions of the pump and that close unbinds
 * the port.
 */
class SerialTcpBridgeTest {

    private class FakeEndpoint : SerialEndpoint {
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile private var sink: ((ByteArray, Int, Int) -> Unit)? = null
        val tapRegistered: Boolean get() = sink != null
        override fun write(bytes: ByteArray) { written.add(bytes) }
        override fun setTap(tap: ((ByteArray, Int, Int) -> Unit)?) { sink = tap }
        /** Simulate the device emitting bytes (as the serial reader thread would). */
        fun emit(bytes: ByteArray) = sink?.invoke(bytes, 0, bytes.size)
    }

    private var bridge: SerialTcpBridge? = null

    @After fun tearDown() { bridge?.close() }

    private fun await(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    @Test
    fun `bytes a client sends reach the device`() {
        val ep = FakeEndpoint()
        val b = SerialTcpBridge(ep, 0).also { it.start() }.also { bridge = it }

        Socket(InetAddress.getLoopbackAddress(), b.port).use { s ->
            s.getOutputStream().apply { write("hello".toByteArray()); flush() }
            await { ep.written.isNotEmpty() }
        }
        assertEquals("hello", ep.written.joinToString("") { String(it) })
    }

    @Test
    fun `bytes the device emits reach the client`() {
        val ep = FakeEndpoint()
        val b = SerialTcpBridge(ep, 0).also { it.start() }.also { bridge = it }

        Socket(InetAddress.getLoopbackAddress(), b.port).use { s ->
            await { ep.tapRegistered && b.clientCount > 0 } // tap registered + client accepted
            ep.emit("world".toByteArray())
            val buf = ByteArray(5)
            val n = s.getInputStream().read(buf)
            assertEquals("world", String(buf, 0, n))
        }
    }

    @Test
    fun `close unbinds the port`() {
        val ep = FakeEndpoint()
        val b = SerialTcpBridge(ep, 0).also { it.start() }
        val port = b.port
        b.close()

        var refused = false
        // The listen socket close is synchronous; a fresh connect should be refused.
        await(1_000) {
            try {
                Socket(InetAddress.getLoopbackAddress(), port).close()
                false
            } catch (_: ConnectException) {
                refused = true
                true
            }
        }
        assertTrue("port $port should be unbound after close", refused)
    }
}
