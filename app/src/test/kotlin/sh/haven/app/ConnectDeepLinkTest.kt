package sh.haven.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.db.entities.ConnectionProfile

/** Unit coverage for the `haven://connect` deep-link parsing + matching (#305). */
class ConnectDeepLinkTest {

    private fun profile(
        id: String = "id",
        host: String = "example.com",
        username: String = "me",
        port: Int = 22,
        type: String = "SSH",
        mosh: Boolean = false,
        et: Boolean = false,
    ) = ConnectionProfile(
        id = id,
        label = host,
        host = host,
        username = username,
        port = port,
        connectionType = type,
        useMosh = mosh,
        useEternalTerminal = et,
    )

    /** A query getter backed by a map, standing in for `Uri::getQueryParameter`. */
    private fun query(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    // --- parse ---

    @Test
    fun `parse returns null when host is absent`() {
        assertNull(ConnectDeepLink.parse(query("user" to "me")))
    }

    @Test
    fun `parse returns null when host is blank`() {
        assertNull(ConnectDeepLink.parse(query("host" to "   ")))
    }

    @Test
    fun `parse extracts and normalises all params`() {
        val p = ConnectDeepLink.parse(
            query(
                "host" to "Example.com",
                "user" to "  me ",
                "port" to "2022",
                "transport" to "  MOSH ",
                "session" to " work ",
            ),
        )!!
        assertEquals("Example.com", p.host)
        assertEquals("me", p.username)
        assertEquals(2022, p.port)
        assertEquals("mosh", p.transport)
        assertEquals("work", p.session)
    }

    @Test
    fun `parse leaves optional fields null when missing or blank`() {
        val p = ConnectDeepLink.parse(query("host" to "h", "user" to "", "session" to ""))!!
        assertNull(p.username)
        assertNull(p.port)
        assertNull(p.transport)
        assertNull(p.session)
    }

    @Test
    fun `parse treats a non-numeric port as unspecified`() {
        val p = ConnectDeepLink.parse(query("host" to "h", "port" to "abc"))!!
        assertNull(p.port)
    }

    // --- matchesTransport ---

    @Test
    fun `matchesTransport distinguishes mosh, et and plain ssh`() {
        val mosh = profile(mosh = true)
        val et = profile(et = true)
        val ssh = profile()
        assertTrue(ConnectDeepLink.matchesTransport(mosh, "mosh"))
        assertTrue(!ConnectDeepLink.matchesTransport(ssh, "mosh"))
        assertTrue(ConnectDeepLink.matchesTransport(et, "et"))
        assertTrue(!ConnectDeepLink.matchesTransport(mosh, "et"))
        // plain "ssh" matches any SSH-family profile, incl. mosh/ET-enabled.
        assertTrue(ConnectDeepLink.matchesTransport(mosh, "ssh"))
        assertTrue(ConnectDeepLink.matchesTransport(ssh, "ssh"))
    }

    // --- matches ---

    @Test
    fun `matches is case-insensitive on host and narrows by user, port, transport`() {
        val a = profile(id = "a", host = "Host.A", username = "me", port = 22)
        val b = profile(id = "b", host = "host.a", username = "other", port = 2222)
        val all = listOf(a, b)

        // Host only (case-insensitive) → both.
        assertEquals(
            setOf("a", "b"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("HOST.A", null, null, null, null)).map { it.id }.toSet(),
        )
        // Narrow by user.
        assertEquals(
            listOf("a"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("host.a", "me", null, null, null)).map { it.id },
        )
        // Narrow by port.
        assertEquals(
            listOf("b"),
            ConnectDeepLink.matches(all, ConnectDeepLink.Params("host.a", null, 2222, null, null)).map { it.id },
        )
    }

    // --- resolve ---

    @Test
    fun `resolve connects when exactly one profile matches`() {
        val match = profile(id = "p1", host = "h", username = "me")
        val cmd = ConnectDeepLink.resolve(
            listOf(match, profile(id = "other", host = "elsewhere")),
            ConnectDeepLink.Params("h", "me", null, null, "work"),
        )
        assertTrue(cmd is AgentUiCommand.ConnectFromDeepLink)
        cmd as AgentUiCommand.ConnectFromDeepLink
        assertEquals("p1", cmd.profileId)
        assertEquals("work", cmd.sessionName)
    }

    @Test
    fun `resolve prefills when no profile matches, defaulting transport to ssh`() {
        val cmd = ConnectDeepLink.resolve(
            emptyList(),
            ConnectDeepLink.Params("newhost", "me", 2022, null, "work"),
        )
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
        cmd as AgentUiCommand.PrefillNewConnection
        assertEquals("newhost", cmd.host)
        assertEquals("me", cmd.username)
        assertEquals(2022, cmd.port)
        assertEquals("ssh", cmd.transport)
        assertEquals("work", cmd.session)
    }

    @Test
    fun `resolve prefills (not connects) when the match is ambiguous`() {
        val a = profile(id = "a", host = "h", username = "me")
        val b = profile(id = "b", host = "h", username = "me")
        val cmd = ConnectDeepLink.resolve(listOf(a, b), ConnectDeepLink.Params("h", "me", null, null, null))
        assertTrue(cmd is AgentUiCommand.PrefillNewConnection)
    }
}
