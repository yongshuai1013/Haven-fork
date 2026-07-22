package sh.haven.core.ssh

import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * An engine-neutral interactive shell channel (#58, phase 5).
 *
 * Callers see the shell as a pair of streams plus resize/teardown/liveness
 * — never the underlying JSch `ChannelShell` or sshlib `SshSession`. Each
 * engine builds one by supplying the streams and the behaviour closures:
 * [openShellOn] for JSch, the sshlib shell path for ssh-proto.
 *
 * The streams must be bound BEFORE the channel is connected: on JSch
 * `getInputStream()` is what installs the server→client pipe, and a write
 * before that pipe exists is silently dropped — which is how the target's
 * SSH banner was lost through a jump host (#381). A shell channel can lose
 * its first output (banner, MOTD, prompt) the same way. Fetch the streams
 * once at construction and pass this object around; never re-fetch.
 */
class ShellChannel(
    val input: InputStream,
    val output: OutputStream,
    private val resizeFn: (cols: Int, rows: Int) -> Unit,
    private val disconnectFn: () -> Unit,
    private val connectedProbe: () -> Boolean,
    private val closedProbe: () -> Boolean,
    private val exitStatusProbe: () -> Int,
) {
    val isConnected: Boolean get() = connectedProbe()
    val isClosed: Boolean get() = closedProbe()

    /** The remote process exit status, or -1 if not yet known / unreported. */
    val exitStatus: Int get() = exitStatusProbe()

    /** Resize the remote PTY (SIGWINCH). */
    fun resize(cols: Int, rows: Int) = resizeFn(cols, rows)

    fun disconnect() = disconnectFn()
}

/**
 * Open an interactive JSch shell channel on [session], binding its streams
 * before connecting it. See [ShellChannel] for why the order matters.
 */
internal fun openShellOn(
    session: Session,
    term: String,
    cols: Int,
    rows: Int,
    agentForwarding: Boolean,
): ShellChannel {
    val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
    channel.setPtyType(term, cols, rows, 0, 0)
    if (agentForwarding) channel.setAgentForwarding(true)
    val input = channel.inputStream
    val output = channel.outputStream
    channel.connect()
    return ShellChannel(
        input = input,
        output = output,
        resizeFn = { c, r -> channel.setPtySize(c, r, 0, 0) },
        disconnectFn = { channel.disconnect() },
        connectedProbe = { channel.isConnected },
        closedProbe = { channel.isClosed },
        exitStatusProbe = { channel.exitStatus },
    )
}
