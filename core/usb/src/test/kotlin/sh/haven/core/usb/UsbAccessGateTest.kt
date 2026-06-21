package sh.haven.core.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessGateTest {

    private val dev = "/dev/bus/usb/001/002"

    @Test
    fun `clear when nothing held`() {
        val g = UsbAccessGate()
        assertFalse(g.isHeld(dev))
        assertTrue(g.awaitClear(dev, 1000))
    }

    @Test
    fun `held after acquire, clear after release`() {
        val g = UsbAccessGate()
        g.acquire(dev)
        assertTrue(g.isHeld(dev))
        g.release(dev)
        assertFalse(g.isHeld(dev))
    }

    @Test
    fun `awaitClear returns false on timeout while held`() {
        val g = UsbAccessGate()
        g.acquire(dev)
        val t0 = System.currentTimeMillis()
        val cleared = g.awaitClear(dev, 80)
        val elapsed = System.currentTimeMillis() - t0
        assertFalse(cleared)
        assertTrue("should wait roughly the timeout, waited ${elapsed}ms", elapsed >= 60)
    }

    @Test
    fun `nested acquire needs matching releases`() {
        val g = UsbAccessGate()
        g.acquire(dev)
        g.acquire(dev)
        g.release(dev)
        assertTrue(g.isHeld(dev))
        g.release(dev)
        assertFalse(g.isHeld(dev))
    }

    @Test
    fun `awaitClear unblocks once another thread releases`() {
        val g = UsbAccessGate()
        g.acquire(dev)
        val releaser = Thread {
            Thread.sleep(50)
            g.release(dev)
        }
        releaser.start()
        val cleared = g.awaitClear(dev, 2000)
        releaser.join()
        assertTrue(cleared)
        assertFalse(g.isHeld(dev))
    }

    @Test
    fun `a lease on one device does not block another`() {
        val g = UsbAccessGate()
        g.acquire(dev)
        // A different device must be clear even while dev is held.
        assertTrue(g.awaitClear("/dev/bus/usb/001/003", 1000))
    }
}
