package sh.haven.feature.sftp.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [rcloneModeToPermissions]. Inputs are the full unix octal mode
 * rclone emits in `Metadata.mode` (with type bits); the renderer must ignore
 * the type bits and take the leading char from `isDir` instead. Fixes #413,
 * where rclone remotes showed a constant `-rw-r--r--`.
 */
class RcloneModeTest {

    private fun render(octal: String, isDir: Boolean = false) =
        rcloneModeToPermissions(octal.toInt(8), isDir)

    @Test fun regular644() = assertEquals("-rw-r--r--", render("100644"))
    @Test fun regular755() = assertEquals("-rwxr-xr-x", render("100755"))
    @Test fun regular600() = assertEquals("-rw-------", render("100600"))
    @Test fun regular640() = assertEquals("-rw-r-----", render("100640"))

    // Type char comes from isDir, not the mode's type bits.
    @Test fun directory755() = assertEquals("drwxr-xr-x", render("40755", isDir = true))
    @Test fun directory700() = assertEquals("drwx------", render("40700", isDir = true))

    // A dir mode with isDir=false (defensive) still renders '-' as the type.
    @Test fun dirModeButNotDir() = assertEquals("-rwxr-xr-x", render("40755", isDir = false))

    @Test fun setuidWithExec() = assertEquals("-rwsr-xr-x", render("104755"))
    @Test fun setuidWithoutExec() = assertEquals("-rwSr-xr-x", render("104655"))
    @Test fun setgidWithExec() = assertEquals("-rwxr-sr-x", render("102755"))
    @Test fun stickyDir() = assertEquals("drwxrwxrwt", render("41777", isDir = true))
}
