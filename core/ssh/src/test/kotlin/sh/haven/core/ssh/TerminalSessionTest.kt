package sh.haven.core.ssh

import com.jcraft.jsch.ChannelShell
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class TerminalSessionTest {

    @Test
    fun `sendToSsh writes bytes to channel output stream`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        val testData = "ls -la\n".toByteArray()
        session.sendToSsh(testData)

        // sendToSsh dispatches to a background executor
        Thread.sleep(200)

        assertArrayEquals(testData, outputStream.toByteArray())

        session.close()
    }

    @Test
    fun `sendToSsh is no-op when channel disconnected`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.sendToSsh("data".toByteArray())

        // sendToSsh guards on !channel.isConnected before submitting to executor
        Thread.sleep(200)

        assertEquals(0, outputStream.size())

        session.close()
    }

    @Test
    fun `reader thread delivers SSH data to onDataReceived callback`() {
        val received = mutableListOf<ByteArray>()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)

        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns pipeIn
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returnsMany listOf(true, true, true, false)
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { data, offset, length ->
                received.add(data.copyOfRange(offset, offset + length))
            },
        )
        session.start()

        // Write test data to the pipe (simulating SSH output)
        val testData = "hello from ssh\n".toByteArray()
        pipeOut.write(testData)
        pipeOut.flush()
        pipeOut.close()

        // Give reader thread time to process
        Thread.sleep(200)

        assertEquals(1, received.size)
        assertArrayEquals(testData, received[0])

        session.close()
    }

    @Test
    fun `resize calls client resizeShell`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.resize(120, 40)

        // resize dispatches to a background executor
        Thread.sleep(200)

        verify { client.resizeShell(channel, 120, 40) }

        session.close()
    }

    @Test
    fun `close disconnects channel`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.close()
        verify { channel.disconnect() }
    }

    @Test
    fun `sendToSsh preserves repeated single-byte sends`() {
        val outputStream = ByteArrayOutputStream()
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns outputStream
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        // Simulate pasting "43339" byte-by-byte (as the emulator delivers it)
        for (b in "43339".toByteArray()) {
            session.sendToSsh(byteArrayOf(b))
        }

        Thread.sleep(200)

        // All characters must be preserved — no dedup
        assertArrayEquals("43339".toByteArray(), outputStream.toByteArray())

        session.close()
    }

    @Test
    fun `reconnect forces a repaint after the session-manager reattach drains`() {
        // Channel 1: the original connection. Its reader sees no data.
        val channel1 = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "near@host",
            channel = channel1,
            client = client,
            onDataReceived = { _, _, _ -> },
        )
        // The user has been using the tab, so a real PTY size is known.
        session.resize(80, 24)
        Thread.sleep(250)

        // attemptReconnect() queues the tmux reattach, then swaps the channel.
        session.setPendingCommands(listOf("exec sh -c 'exec tmux new-session -A -s near'"))

        val out2 = ByteArrayOutputStream()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val channel2 = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns pipeIn
            every { getOutputStream() } returns out2
            every { isConnected } returns true
        }
        session.reconnect(channel2, client)

        // The fresh login shell prints its prompt; reattach fires on the '$'.
        pipeOut.write("droid@host:~$ ".toByteArray())
        pipeOut.flush()

        // Allow prompt detection + the scheduled post-reattach redraw (450+150ms).
        Thread.sleep(900)

        // Reattach command went out on the new channel.
        assertTrue(
            "expected reattach command on new channel, got: '${String(out2.toByteArray())}'",
            String(out2.toByteArray()).contains("tmux new-session -A -s near"),
        )
        // Repaint wobble: a genuine size change (80x23) then restore (80x24)
        // on the *new* channel, forcing tmux to redraw the reattached pane.
        verify { client.resizeShell(channel2, 80, 23) }
        verify { client.resizeShell(channel2, 80, 24) }

        session.close()
    }

    @Test
    fun `close is idempotent`() {
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { getOutputStream() } returns ByteArrayOutputStream()
            every { isConnected } returns false
        }
        val client = mockk<SshClient>(relaxed = true)

        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "test@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
        )

        session.close()
        session.close() // Should not throw
        // channel.disconnect() called once due to relaxed mock
    }

    @Test
    fun `pending command fires on a custom prompt char only once it is configured`() {
        val out = ByteArrayOutputStream()
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut)
        val channel = mockk<ChannelShell>(relaxed = true) {
            every { inputStream } returns pipeIn
            every { getOutputStream() } returns out
            every { isConnected } returns true
        }
        val client = mockk<SshClient>(relaxed = true)

        // A prompt ending in '»' — none of the built-in $ # % > ❯ terminators.
        val session = TerminalSession(
            sessionId = "test-session",
            profileId = "test",
            label = "near@host",
            channel = channel,
            client = client,
            onDataReceived = { _, _, _ -> },
            pendingCommands = listOf("tmux attach"),
        )
        try {
            session.start()

            // Default terminators don't include '»', so the queued command stays put.
            pipeOut.write("ian@host » ".toByteArray())
            pipeOut.flush()
            Thread.sleep(300)
            assertTrue(
                "command should NOT fire before '»' is configured, got: '${String(out.toByteArray())}'",
                !String(out.toByteArray()).contains("tmux attach"),
            )

            // User adds '»' as a prompt character (#280): now the prompt is detected.
            TerminalSession.promptTerminators =
                TerminalSession.DEFAULT_PROMPT_TERMINATORS + '»'
            pipeOut.write("ian@host » ".toByteArray())
            pipeOut.flush()
            Thread.sleep(300)
            assertTrue(
                "command should fire once '»' is configured, got: '${String(out.toByteArray())}'",
                String(out.toByteArray()).contains("tmux attach"),
            )
        } finally {
            // Restore the process-wide default for other tests.
            TerminalSession.promptTerminators = TerminalSession.DEFAULT_PROMPT_TERMINATORS
            session.close()
        }
    }
}
