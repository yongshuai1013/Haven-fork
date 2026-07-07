package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.proot.PackageFamily

/**
 * [qemuVncCommand] / [qemuPackageFor] are the pure, Context-free pieces of the
 * #326 system-VM launch — pinned here so the exact qemu args the on-device
 * spike proved (VNC on loopback, std VGA, boot-from-disk) can't silently
 * regress. The device path (proc launch, port-bind wait) is covered on-device.
 */
class SystemVmManagerTest {

    @Test
    fun `vnc command binds VNC on the derived display and boots the disk`() {
        val cmd = qemuVncCommand(diskGuestPath = "/tmp/system-vm/sys.qcow2", display = 7, memMb = 2048, cpus = 2)
        // Display 7 → qemu binds 127.0.0.1:5907. The VNC server is what Haven's
        // viewer connects to; std VGA is the surface it renders.
        assertTrue("must serve VNC on the derived display", cmd.contains("-vnc 127.0.0.1:7"))
        assertTrue("std VGA gives the client a surface to render", cmd.contains("-vga std"))
        assertTrue("boots the installed disk", cmd.contains("-boot c"))
        // Imported images are normalised to qcow2 (the default format).
        assertTrue("virtio disk in the default qcow2 format", cmd.contains("-drive file=/tmp/system-vm/sys.qcow2,if=virtio,format=qcow2"))
        assertTrue("user-net for guest outbound", cmd.contains("-netdev user,id=n0"))
        assertTrue("exec so the launcher process IS qemu (clean to signal)", cmd.startsWith("exec qemu-system-x86_64"))
    }

    @Test
    fun `disk format is honoured — raw for a hand-placed image, qcow2 by default`() {
        assertTrue(qemuVncCommand("/d.img", 1, 2048, 2, diskFormat = "raw").contains("format=raw"))
        assertTrue("default is qcow2 (imported images)", qemuVncCommand("/d.qcow2", 1, 2048, 2).contains("format=qcow2"))
    }

    @Test
    fun `vnc command honours mem and cpu sizing`() {
        val cmd = qemuVncCommand("/d.img", display = 0, memMb = 4096, cpus = 4)
        assertTrue(cmd.contains("-m 4096"))
        assertTrue(cmd.contains("-smp 4"))
        assertTrue("display 0 → 127.0.0.1:0 → port 5900", cmd.contains("-vnc 127.0.0.1:0"))
    }

    @Test
    fun `no serial-console redirection — a system VM is driven via VNC, not serial`() {
        val cmd = qemuVncCommand("/d.img", display = 1, memMb = 2048, cpus = 2)
        // Unlike the USB appliance (serial auto-drive), a user-facing system VM
        // is interactive over VNC; a `-serial stdio` here would fight that.
        assertFalse(cmd.contains("-serial stdio"))
    }

    @Test
    fun `qemu package name is per-distro-family — Debian's is VNC-capable`() {
        assertEquals("qemu-system-x86_64", qemuPackageFor(PackageFamily.APK))
        assertEquals("qemu-system-x86", qemuPackageFor(PackageFamily.APT))
        assertEquals("qemu-system-x86", qemuPackageFor(PackageFamily.PACMAN))
        assertEquals("qemu", qemuPackageFor(PackageFamily.XBPS))
    }
}
