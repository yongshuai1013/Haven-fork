package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ShellChannel
import sh.haven.core.ssh.SshIoException

/**
 * Opens an interactive shell [ShellChannel] over a connected sshlib client
 * (#58, phase 5): a session channel with a PTY and a shell request, its
 * `stdout` bridged to a blocking [ReceiveChannelInputStream] and stdin to a
 * [SuspendWriteOutputStream] — the same stream shape
 * [sh.haven.core.ssh.TerminalSession] already consumes from JSch.
 */
internal object SshlibShell {

    fun open(
        client: SshlibClient,
        session: SshSession,
        term: String,
        cols: Int,
        rows: Int,
    ): ShellChannel {
        val input = ReceiveChannelInputStream(session.stdout)
        val output = SuspendWriteOutputStream { session.write(it) }
        return ShellChannel(
            input = input,
            output = output,
            resizeFn = { c, r ->
                runBlocking { session.resizeTerminal(c, r, 0, 0) }
            },
            disconnectFn = {
                runCatching { session.close() }
                runCatching { runBlocking { client.disconnect() } }
            },
            connectedProbe = { session.isOpen },
            closedProbe = { !session.isOpen },
            // sshlib exit-status is not in the released jar (upstreamed as
            // cbssh#232); until it ships, an interactive shell reports -1
            // ("unknown"), matching JSch before the server sends exit-status.
            exitStatusProbe = { -1 },
        )
    }

    /** Open a session channel, request a PTY, and start a shell. */
    suspend fun requestShell(
        session: SshSession,
        term: String,
        cols: Int,
        rows: Int,
    ) {
        if (!session.requestPty(term, cols, rows)) {
            throw SshIoException("sshlib: server rejected PTY request")
        }
        if (!session.requestShell()) {
            throw SshIoException("sshlib: server rejected shell request")
        }
    }
}
