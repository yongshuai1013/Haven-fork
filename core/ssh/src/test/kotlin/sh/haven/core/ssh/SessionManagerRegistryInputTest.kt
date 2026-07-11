package sh.haven.core.ssh

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager

/**
 * #366 — send_terminal_input only tried SSH + local, so input to a mosh/ET/
 * Reticulum session failed "No local session" while snapshot reads resolved
 * the same id. Pins the registry dispatcher: input reaches whichever
 * transport owns the session, and the no-owner error names all transports.
 */
class SessionManagerRegistryInputTest {

    private val ssh = mockk<SshSessionManager>(relaxed = true)
    private val local = mockk<LocalSessionManager>(relaxed = true)
    private val mosh = mockk<MoshSessionManager>(relaxed = true)
    private val et = mockk<EtSessionManager>(relaxed = true)
    private val reticulum = mockk<ReticulumSessionManager>(relaxed = true)

    private fun registry() = SessionManagerRegistry(
        ssh = ssh,
        reticulum = reticulum,
        mosh = mosh,
        et = et,
        smb = mockk(relaxed = true),
        local = local,
        rdp = mockk(relaxed = true),
        mail = mockk(relaxed = true),
        rclone = mockk(relaxed = true),
        keepAlives = emptySet(),
    )

    private fun disown(vararg managers: Any, transport: (Any) -> String) {
        // relaxed mocks silently succeed — non-owners must throw like the real
        // managers do, or the chain would stop at the first mock.
        for (m in managers) {
            val msg = "No ${transport(m)} session: s1"
            when (m) {
                is SshSessionManager -> every { m.sendInput(any(), any()) } throws IllegalStateException(msg)
                is LocalSessionManager -> every { m.sendInput(any(), any()) } throws IllegalStateException(msg)
                is MoshSessionManager -> every { m.sendInput(any(), any()) } throws IllegalStateException(msg)
                is EtSessionManager -> every { m.sendInput(any(), any()) } throws IllegalStateException(msg)
                is ReticulumSessionManager -> every { m.sendInput(any(), any()) } throws IllegalStateException(msg)
            }
        }
    }

    private fun name(m: Any) = when (m) {
        ssh -> "SSH"; local -> "local"; mosh -> "mosh"; et -> "ET"; reticulum -> "Reticulum"
        else -> "?"
    }

    @Test
    fun `input reaches a mosh-owned session`() {
        disown(ssh, local, et, reticulum, transport = ::name)
        every { mosh.sendInput("s1", "ls\r") } just runs

        registry().sendTerminalInput("s1", "ls\r")

        verify(exactly = 1) { mosh.sendInput("s1", "ls\r") }
    }

    @Test
    fun `input reaches an ET-owned session`() {
        disown(ssh, local, mosh, reticulum, transport = ::name)
        every { et.sendInput("s1", "x") } just runs

        registry().sendTerminalInput("s1", "x")

        verify(exactly = 1) { et.sendInput("s1", "x") }
    }

    @Test
    fun `no owner names all transports`() {
        disown(ssh, local, mosh, et, reticulum, transport = ::name)

        try {
            registry().sendTerminalInput("s1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            for (t in listOf("SSH", "local", "mosh", "ET", "Reticulum")) {
                assertTrue("error should name $t: \"$msg\"", msg.contains(t))
            }
        }
    }

    @Test
    fun `owner's diagnosis wins over not-mine errors`() {
        disown(local, mosh, et, reticulum, transport = ::name)
        every { ssh.sendInput(any(), any()) } throws
            IllegalStateException("Session s1 has no active terminal — open a terminal tab first")

        try {
            registry().sendTerminalInput("s1", "x")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "expected the SSH diagnosis, got \"${e.message}\"",
                e.message!!.contains("has no active terminal"),
            )
        }
    }
}
