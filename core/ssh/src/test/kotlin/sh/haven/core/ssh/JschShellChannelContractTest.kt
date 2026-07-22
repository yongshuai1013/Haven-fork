package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.junit.After

/** [ShellChannelContractTest] on the JSch engine — the behaviour the sshlib shell must match. */
class JschShellChannelContractTest : ShellChannelContractTest() {

    private var client: SshClient? = null

    override fun openShell(host: String, port: Int, username: String, password: String): ShellChannel {
        val c = SshClient()
        client = c
        runBlocking {
            c.connect(
                ConnectionConfig(
                    host = host,
                    port = port,
                    username = username,
                    authMethod = ConnectionConfig.AuthMethod.Password(password),
                ),
            )
        }
        return c.openShellChannel(term = "xterm", cols = 80, rows = 24)
    }

    @After
    fun closeClient() {
        try { client?.close() } catch (_: Exception) { /* best effort */ }
    }
}
