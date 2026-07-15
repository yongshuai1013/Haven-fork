package sh.haven.core.btserial

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [BtSerialTransport] backed by plain piped streams — the device's "output"
 * is what the test writes into [deviceToApp]; the app's keystrokes land in
 * [appToDevice]. No Bluetooth involved.
 */
private class FakeTransport : BtSerialTransport {
    private val deviceSink = PipedOutputStream()
    val deviceToApp = PipedInputStream(deviceSink, 64 * 1024)
    val appToDevice = ByteArrayOutputStream()
    val closed = AtomicBoolean(false)

    override val input: InputStream get() = deviceToApp
    override val output = appToDevice
    override val remoteName: String? = "Bench Console"
    override fun close() {
        closed.set(true)
        runCatching { deviceSink.close() }
    }

    /** Simulate the device sending bytes to the app. */
    fun deviceSends(bytes: ByteArray) {
        deviceSink.write(bytes)
        deviceSink.flush()
    }
}

class BtSerialSessionTest {

    @Test
    fun `device output reaches the terminal callback`() {
        val transport = FakeTransport()
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val session = BtSerialSession(
            sessionId = "s1",
            transport = transport,
            onDataReceived = { buf, off, len ->
                received.write(buf, off, len)
                if (received.size() >= 7) latch.countDown()
            },
            onDisconnected = {},
        )
        session.start()

        transport.deviceSends("Switch>".toByteArray())
        assertTrue("callback should receive device bytes", latch.await(2, TimeUnit.SECONDS))
        assertEquals("Switch>", received.toString("UTF-8"))
        session.close()
    }

    @Test
    fun `keystrokes are written to the device`() {
        val transport = FakeTransport()
        val session = BtSerialSession("s2", transport, onDataReceived = { _, _, _ -> }, onDisconnected = {})
        session.start()

        session.sendInput("enable\n".toByteArray())
        assertArrayEquals("enable\n".toByteArray(), transport.appToDevice.toByteArray())
        session.close()
    }

    @Test
    fun `remote hangup fires onDisconnected exactly once`() {
        val transport = FakeTransport()
        val dropped = CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val session = BtSerialSession(
            "s3", transport,
            onDataReceived = { _, _, _ -> },
            onDisconnected = { count.incrementAndGet(); dropped.countDown() },
        )
        session.start()

        transport.close() // device end goes away → read loop hits EOF/error
        assertTrue("onDisconnected should fire on hangup", dropped.await(2, TimeUnit.SECONDS))
        assertEquals(1, count.get())
        assertTrue(session.isClosed)
    }

    @Test
    fun `explicit close does NOT fire onDisconnected`() {
        val transport = FakeTransport()
        val fired = AtomicBoolean(false)
        val session = BtSerialSession(
            "s4", transport,
            onDataReceived = { _, _, _ -> },
            onDisconnected = { fired.set(true) },
        )
        session.start()

        session.close()
        Thread.sleep(200) // let the reader unwind
        assertTrue("transport closed", transport.closed.get())
        assertFalse("user teardown must not look like a drop", fired.get())
        assertTrue(session.isClosed)
    }

    @Test
    fun `detach stops delivery, reattach resumes it`() {
        val transport = FakeTransport()
        val a = ByteArrayOutputStream()
        val session = BtSerialSession("s5", transport, onDataReceived = { b, o, l -> a.write(b, o, l) }, onDisconnected = {})
        session.start()

        session.detach()
        transport.deviceSends("lost".toByteArray())
        Thread.sleep(150)
        assertEquals("no delivery while detached", 0, a.size())

        val b = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        session.reattach { buf, off, len -> b.write(buf, off, len); latch.countDown() }
        transport.deviceSends("kept".toByteArray())
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals("kept", b.toString("UTF-8"))
        session.close()
    }

    @Test
    fun `manager connect-create-send-remove lifecycle`() = runTest {
        val transport = FakeTransport()
        val mgr = BtSerialSessionManager(context = mockContext()).apply {
            openTransport = { _, _ -> transport }
        }
        val id = mgr.registerSession("p1", "core-switch", deviceAddress = "AA:BB:CC:DD:EE:FF")
        assertEquals(BtSerialSessionManager.SessionState.Status.CONNECTING, mgr.sessions.value[id]!!.status)

        val session = mgr.createTerminalSession(id) { _, _, _ -> }
        assertTrue(session != null)
        assertEquals(BtSerialSessionManager.SessionState.Status.CONNECTED, mgr.sessions.value[id]!!.status)
        assertTrue(mgr.isProfileConnected("p1"))

        mgr.sendInput(id, "show version\n")
        assertArrayEquals("show version\n".toByteArray(), transport.appToDevice.toByteArray())

        mgr.removeSession(id)
        assertFalse(mgr.sessions.value.containsKey(id))
    }

    // The manager only touches Context inside AndroidBtSerialConnector, which the
    // openTransport seam replaces in tests — so a relaxed stub is enough.
    private fun mockContext(): android.content.Context = io.mockk.mockk(relaxed = true)
}
