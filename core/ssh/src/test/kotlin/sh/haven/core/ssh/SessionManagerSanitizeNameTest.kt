package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #358: tmux uses '.' and ':' as session:window.pane separators, so a
 * session name auto-derived from user@host with an IP host (user-10.0.0.5)
 * makes `tmux new-session -A -s <name>` fail — the exec'd shell closes and
 * the user sees a misleading "is your session manager installed?" error.
 */
class SessionManagerSanitizeNameTest {

    @Test
    fun `dots are replaced`() {
        assertEquals("user-10-0-0-5", SessionManager.sanitizeSessionName("user-10.0.0.5"))
    }

    @Test
    fun `colons are replaced`() {
        assertEquals("host-1-2", SessionManager.sanitizeSessionName("host:1.2"))
    }

    @Test
    fun `allowed characters pass through`() {
        assertEquals("my_host-2", SessionManager.sanitizeSessionName("my_host-2"))
    }

    @Test
    fun `spaces and shell metacharacters are replaced`() {
        assertEquals("my-session--rm--rf-", SessionManager.sanitizeSessionName("my session; rm -rf!"))
    }
}
