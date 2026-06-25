package sh.haven.core.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for #262: an extract interrupted partway (app killed
 * mid-install) used to leave a rootfs with `bin/sh` but little else, which
 * counted as "installed" forever. A rootfs is now complete only when the
 * [ROOTFS_READY_MARKER] — written at the end of a successful install — is
 * also present.
 */
class ProotManagerInstallMarkerTest {

    private fun rootfsWithBinSh(): File {
        val root = Files.createTempDirectory("proot-marker-test").toFile()
        File(root, "bin").mkdirs()
        File(root, "bin/sh").writeText("#!/bin/sh\n")
        return root
    }

    @Test
    fun `files-present is true once bin-sh exists`() {
        val root = rootfsWithBinSh()
        assertTrue(rootfsFilesPresent(root))
        forceDeleteRecursively(root)
    }

    @Test
    fun `partial extract (bin-sh but no marker) is NOT complete`() {
        val root = rootfsWithBinSh()
        assertTrue("setup: files are present", rootfsFilesPresent(root))
        assertFalse("a rootfs without the ready marker is incomplete", isRootfsComplete(root))
        forceDeleteRecursively(root)
    }

    @Test
    fun `bin-sh plus ready marker is complete`() {
        val root = rootfsWithBinSh()
        File(root, ROOTFS_READY_MARKER).writeText("ready\n")
        assertTrue(isRootfsComplete(root))
        forceDeleteRecursively(root)
    }

    @Test
    fun `marker without bin-sh is NOT complete`() {
        val root = Files.createTempDirectory("proot-marker-only").toFile()
        File(root, ROOTFS_READY_MARKER).writeText("ready\n")
        assertFalse("marker alone, with no rootfs files, is not a usable install", isRootfsComplete(root))
        forceDeleteRecursively(root)
    }

    @Test
    fun `missing directory is neither present nor complete`() {
        val missing = File(Files.createTempDirectory("proot-marker-missing").toFile(), "nope")
        assertFalse(rootfsFilesPresent(missing))
        assertFalse(isRootfsComplete(missing))
    }
}
