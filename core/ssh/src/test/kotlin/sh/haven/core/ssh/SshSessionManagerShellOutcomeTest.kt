package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pin the deterministic connect outcome (#215 follow-up): a shell that closes
 * immediately (no session manager on the host) must report [ShellOutcome.ShellClosed]
 * rather than reading as a successful connect, so the caller surfaces an error
 * and never navigates to a phantom terminal tab.
 */
class SshSessionManagerShellOutcomeTest {

    private fun newManager() = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))

    private fun mockChannel(
        input: InputStream,
        connected: Boolean,
        closed: Boolean = !connected,
        exit: Int = 0,
    ): ChannelShell {
        val ch = mockk<ChannelShell>(relaxed = true)
        every { ch.inputStream } returns input
        every { ch.outputStream } returns ByteArrayOutputStream()
        every { ch.isConnected } returns connected
        every { ch.isClosed } returns closed
        every { ch.exitStatus } returns exit
        return ch
    }

    @Test
    fun `ShellClosed when the remote shell EOFs immediately`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        // Empty stream => read() returns -1 at once (EOF); channel closed with a
        // clean exit status => the "no session manager" shell-closed case.
        every { client.openShellChannel() } returns mockChannel(
            input = ByteArrayInputStream(ByteArray(0)),
            connected = false, closed = true, exit = 0,
        )
        val sessionId = mgr.registerSession("p1", "S", client)

        val outcome = mgr.openShellAndAwaitReady(sessionId, settleMs = 2000)

        assertTrue("expected ShellClosed, got $outcome", outcome is ShellOutcome.ShellClosed)
        assertEquals(
            SshSessionManager.SessionState.Status.ERROR,
            mgr.getSession(sessionId)!!.status,
        )
    }

    @Test
    fun `Failed when openShellChannel throws`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        every { client.openShellChannel() } throws RuntimeException("channel refused")
        val sessionId = mgr.registerSession("p1", "S", client)

        val outcome = mgr.openShellAndAwaitReady(sessionId)

        assertTrue("expected Failed, got $outcome", outcome is ShellOutcome.Failed)
        assertEquals("channel refused", (outcome as ShellOutcome.Failed).reason)
        assertEquals(
            SshSessionManager.SessionState.Status.ERROR,
            mgr.getSession(sessionId)!!.status,
        )
    }

    @Test
    fun `Ready when the reader stays alive past the settle window`() = runBlocking {
        val mgr = newManager()
        val client = mockk<SshClient>(relaxed = true)
        // A reader that blocks (no EOF) models a healthy interactive shell.
        val blocking = object : InputStream() {
            override fun read(): Int { Thread.sleep(5_000); return -1 }
            override fun read(b: ByteArray): Int { Thread.sleep(5_000); return -1 }
        }
        every { client.openShellChannel() } returns mockChannel(input = blocking, connected = true)
        val sessionId = mgr.registerSession("p1", "S", client)

        val outcome = mgr.openShellAndAwaitReady(sessionId, settleMs = 300)

        assertTrue("expected Ready, got $outcome", outcome is ShellOutcome.Ready)
    }

    @Test
    fun `Failed when session does not exist`() = runBlocking {
        val mgr = newManager()
        val outcome = mgr.openShellAndAwaitReady("nope")
        assertTrue("expected Failed, got $outcome", outcome is ShellOutcome.Failed)
    }
}
