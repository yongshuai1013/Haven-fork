package sh.haven.core.usbserial

import android.hardware.usb.UsbDevice
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [UsbSerialLink] with no hardware: the device's "output" is whatever the test
 * feeds via [deviceSends]; the app's keystrokes accumulate in [appToDevice].
 */
private class FakeLink : UsbSerialLink {
    val appToDevice = ByteArrayOutputStream()
    val closed = AtomicBoolean(false)
    private var onData: ((ByteArray) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    override val displayName: String? = "Bench Adapter"

    override fun start(onData: (ByteArray) -> Unit, onError: (Throwable) -> Unit) {
        this.onData = onData
        this.onError = onError
    }

    override fun write(bytes: ByteArray) {
        appToDevice.write(bytes)
    }

    override fun close() {
        closed.set(true)
    }

    fun deviceSends(bytes: ByteArray) = onData?.invoke(bytes)

    /** Simulate the cable being pulled / adapter erroring mid-session. */
    fun deviceHangup() = onError?.invoke(RuntimeException("USB detached"))
}

class UsbSerialSessionTest {

    @Test
    fun `device output reaches the terminal callback`() {
        val link = FakeLink()
        val received = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val session = UsbSerialSession(
            sessionId = "s1",
            link = link,
            onDataReceived = { buf, off, len ->
                received.write(buf, off, len)
                if (received.size() >= 3) latch.countDown()
            },
            onDisconnected = {},
        )
        session.start()

        link.deviceSends("ok\n".toByteArray())
        assertTrue("callback should receive device bytes", latch.await(2, TimeUnit.SECONDS))
        assertEquals("ok\n", received.toString("UTF-8"))
        session.close()
    }

    @Test
    fun `keystrokes are written to the device`() {
        val link = FakeLink()
        val session = UsbSerialSession("s2", link, onDataReceived = { _, _, _ -> }, onDisconnected = {})
        session.start()

        session.sendInput("G28\n".toByteArray())
        assertArrayEquals("G28\n".toByteArray(), link.appToDevice.toByteArray())
        session.close()
    }

    @Test
    fun `cable pull fires onDisconnected exactly once`() {
        val link = FakeLink()
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val session = UsbSerialSession(
            "s3", link,
            onDataReceived = { _, _, _ -> },
            onDisconnected = { count.incrementAndGet() },
        )
        session.start()

        link.deviceHangup()
        link.deviceHangup() // a second error must not double-report
        assertEquals(1, count.get())
        assertTrue(session.isClosed)
        assertTrue("link released on drop", link.closed.get())
    }

    @Test
    fun `explicit close does NOT fire onDisconnected`() {
        val link = FakeLink()
        val fired = AtomicBoolean(false)
        val session = UsbSerialSession(
            "s4", link,
            onDataReceived = { _, _, _ -> },
            onDisconnected = { fired.set(true) },
        )
        session.start()

        session.close()
        assertTrue("link closed", link.closed.get())
        assertFalse("user teardown must not look like a drop", fired.get())
        assertTrue(session.isClosed)
    }

    @Test
    fun `detach stops delivery, reattach resumes it`() {
        val link = FakeLink()
        val a = ByteArrayOutputStream()
        val session = UsbSerialSession("s5", link, onDataReceived = { b, o, l -> a.write(b, o, l) }, onDisconnected = {})
        session.start()

        session.detach()
        link.deviceSends("lost".toByteArray())
        assertEquals("no delivery while detached", 0, a.size())

        val b = ByteArrayOutputStream()
        session.reattach { buf, off, len -> b.write(buf, off, len) }
        link.deviceSends("kept".toByteArray())
        assertEquals("kept", b.toString("UTF-8"))
        session.close()
    }

    @Test
    fun `manager connect-create-send-remove lifecycle`() = runTest {
        val link = FakeLink()
        val mgr = UsbSerialSessionManager(context = mockk(relaxed = true)).apply {
            resolveDevice = { mockk<UsbDevice>(relaxed = true) }
            openLink = { _, _ -> link }
        }
        val id = mgr.registerSession("p1", "duet", deviceKey = "1d50:60ec")
        assertEquals(UsbSerialSessionManager.SessionState.Status.CONNECTING, mgr.sessions.value[id]!!.status)

        mgr.connectSession(id)
        assertEquals(UsbSerialSessionManager.SessionState.Status.CONNECTED, mgr.sessions.value[id]!!.status)
        assertTrue(mgr.isReadyForTerminal(id))

        val session = mgr.createTerminalSession(id) { _, _, _ -> }
        assertTrue(session != null)
        assertTrue(mgr.isProfileConnected("p1"))

        mgr.sendInput(id, "M115\n")
        assertArrayEquals("M115\n".toByteArray(), link.appToDevice.toByteArray())

        mgr.removeSession(id)
        assertFalse(mgr.sessions.value.containsKey(id))
    }

    @Test
    fun `unattached device surfaces ERROR`() = runTest {
        val mgr = UsbSerialSessionManager(context = mockk(relaxed = true)).apply {
            resolveDevice = { null } // nothing plugged in
        }
        val id = mgr.registerSession("p2", "gone", deviceKey = "1a86:7523")
        runCatching { mgr.connectSession(id) }
        assertEquals(UsbSerialSessionManager.SessionState.Status.ERROR, mgr.sessions.value[id]!!.status)
    }

    @Test
    fun `deviceKey round-trips through parseKey`() {
        assertEquals("1a86:7523", UsbSerialSessionManager.deviceKey(0x1a86, 0x7523))
        assertEquals(0x1a86 to 0x7523, UsbSerialSessionManager.parseKey("1a86:7523"))
        assertNull(UsbSerialSessionManager.parseKey("nonsense"))
    }
}
