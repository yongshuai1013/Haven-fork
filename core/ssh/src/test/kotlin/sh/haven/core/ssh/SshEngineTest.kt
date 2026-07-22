package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SshEngineTest {

    private fun config(options: Map<String, String>) = ConnectionConfig(
        host = "example.com",
        username = "u",
        sshOptions = options,
    )

    @Test
    fun `absent directive defaults to JSCH`() {
        assertEquals(SshEngine.JSCH, config(emptyMap()).sshEngine)
    }

    @Test
    fun `directive is case-insensitive in key and value`() {
        assertEquals(SshEngine.SSHLIB, config(mapOf("havensshengine" to "SSHLIB")).sshEngine)
        assertEquals(SshEngine.SSHLIB, config(mapOf("HavenSshEngine" to "sshlib")).sshEngine)
    }

    @Test
    fun `unknown value falls back to JSCH, never throws`() {
        assertEquals(SshEngine.JSCH, config(mapOf("HavenSshEngine" to "openssh")).sshEngine)
        assertEquals(SshEngine.JSCH, config(mapOf("HavenSshEngine" to "")).sshEngine)
    }

    @Test
    fun `directive round-trips through parseSshOptions`() {
        val options = ConnectionConfig.parseSshOptions("HavenSshEngine sshlib\nServerAliveInterval 30")
        assertEquals(SshEngine.SSHLIB, config(options).sshEngine)
    }
}
