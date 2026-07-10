package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.local.proot.PackageFamily

/**
 * #368: uninstalling one desktop must not remove a package another installed
 * desktop still needs. Custom-X11 and Native-X11 both ship `xterm` (and every
 * VNC desktop ships `tigervnc`); the old uninstall ran `apk del <full list>`,
 * deleting the sibling's detection binary and corrupting its state — which the
 * marker rewrite then trusted, producing the reported install-state loop.
 * These pin [desktopPackagesToRemove], the pure retain-set the fixed
 * [ProotManager.uninstallDesktop] now computes from the pre-removal snapshot.
 */
class DesktopUninstallPackagesTest {

    private fun apkPkgs(de: ProotManager.DesktopEnvironment): List<String> =
        de.spec.packagesPerFamily[PackageFamily.APK]
            ?: error("${de.id} has no APK package list")

    @Test
    fun `uninstalling Custom-X11 with Native-X11 present retains the shared xterm`() {
        val custom = apkPkgs(ProotManager.DesktopEnvironment.CUSTOM_X11)
        val native = apkPkgs(ProotManager.DesktopEnvironment.X11_NATIVE)
        assertTrue("catalog changed: Custom-X11 no longer ships xterm", "xterm" in custom)
        assertTrue("catalog changed: Native-X11 no longer ships xterm", "xterm" in native)

        val toRemove = desktopPackagesToRemove(custom, listOf(native))

        assertFalse("xterm is Native-X11's detection binary — must be retained", "xterm" in toRemove)
        assertTrue("tigervnc is unique to Custom-X11 — should be removed", "tigervnc" in toRemove)
        assertTrue("dbus-x11 is unique to Custom-X11 — should be removed", "dbus-x11" in toRemove)
    }

    @Test
    fun `uninstalling Native-X11 with Custom-X11 present retains the shared xterm`() {
        val custom = apkPkgs(ProotManager.DesktopEnvironment.CUSTOM_X11)
        val native = apkPkgs(ProotManager.DesktopEnvironment.X11_NATIVE)

        val toRemove = desktopPackagesToRemove(native, listOf(custom))

        assertFalse("xterm is Custom-X11's detection binary — must be retained", "xterm" in toRemove)
        assertTrue("xwayland is unique to Native-X11 — should be removed", "xwayland" in toRemove)
        assertTrue("mesa-demos is unique to Native-X11 — should be removed", "mesa-demos" in toRemove)
    }

    @Test
    fun `with no other desktop installed every package is removed`() {
        val custom = apkPkgs(ProotManager.DesktopEnvironment.CUSTOM_X11)
        assertEquals(custom, desktopPackagesToRemove(custom, emptyList()))
    }

    @Test
    fun `a package shared with any of several installed desktops is retained`() {
        // tigervnc is shared with the Openbox VNC desktop too — retaining it
        // must consider ALL other installed desktops, not just one.
        val custom = apkPkgs(ProotManager.DesktopEnvironment.CUSTOM_X11)
        val openbox = apkPkgs(ProotManager.DesktopEnvironment.OPENBOX)
        assertTrue("catalog changed: Openbox no longer ships tigervnc", "tigervnc" in openbox)

        val toRemove = desktopPackagesToRemove(custom, listOf(openbox))

        assertFalse("tigervnc shared with Openbox — must be retained", "tigervnc" in toRemove)
        assertTrue("dbus-x11 unique to Custom-X11 — should be removed", "dbus-x11" in toRemove)
    }
}
