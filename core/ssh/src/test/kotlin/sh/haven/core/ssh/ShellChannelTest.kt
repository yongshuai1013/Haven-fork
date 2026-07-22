package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Same trap as [ProxyJumpTest], on the shell channel: JSch installs the
 * server→client pipe inside `Channel.getInputStream()` and `Channel.write()`
 * swallows the NPE when it isn't there yet, so output the remote sends before
 * the pipe exists (banner, MOTD, first prompt) is silently dropped. Bind the
 * streams before connect, and hand them out — fetching them again downstream
 * would install a fresh pipe and orphan what the first one buffered (#382).
 */
class ShellChannelTest {

    @Test
    fun `binds shell streams before connecting the channel`() {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true)
        every { channel.inputStream } returns input
        every { channel.outputStream } returns output
        val session = mockk<Session>()
        every { session.openChannel("shell") } returns channel

        val shell = openShellOn(session, "xterm-256color", 80, 24, agentForwarding = false)

        verifyOrder {
            channel.setPtyType("xterm-256color", 80, 24, 0, 0)
            channel.inputStream
            channel.outputStream
            channel.connect()
        }
        assertSame(input, shell.input)
        assertSame(output, shell.output)
        // resize/disconnect now delegate to the JSch channel via the neutral
        // ShellChannel closures (#58 phase 5) rather than exposing it.
        shell.resize(120, 40)
        verify { channel.setPtySize(120, 40, 0, 0) }
        shell.disconnect()
        verify { channel.disconnect() }
    }

    @Test
    fun `enables agent forwarding before connect when asked`() {
        val channel = mockk<ChannelShell>(relaxed = true)
        every { channel.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.openChannel("shell") } returns channel

        openShellOn(session, "xterm", 100, 30, agentForwarding = true)

        verifyOrder {
            channel.setAgentForwarding(true)
            channel.connect()
        }
    }

    @Test
    fun `does not forward the agent by default`() {
        val channel = mockk<ChannelShell>(relaxed = true)
        every { channel.inputStream } returns ByteArrayInputStream(ByteArray(0))
        every { channel.outputStream } returns ByteArrayOutputStream()
        val session = mockk<Session>()
        every { session.openChannel("shell") } returns channel

        openShellOn(session, "xterm", 80, 24, agentForwarding = false)

        verify(exactly = 0) { channel.setAgentForwarding(any()) }
    }
}
