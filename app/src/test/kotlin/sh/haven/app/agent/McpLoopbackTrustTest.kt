package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.font.TerminalFontInstaller
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.ffmpeg.FfmpegExecutor
import sh.haven.core.ffmpeg.HlsStreamServer
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.rclone.RcloneClient
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.feature.sftp.SftpStreamServer

/**
 * #214 / #mcp-backbone Stage 2 — origin-tagged loopback auto-trust. A client
 * arriving on the DEVICE-origin binder (`adb forward` / on-device) skips
 * BOTH the pairing prompt and per-action consent, but ONLY when the user has
 * opted in via [UserPreferencesRepository.trustLoopbackMcpClients] (default
 * OFF). TUNNELED / LAN / WireGuard origins keep the full gate regardless —
 * including reverse-tunneled traffic that physically arrives on 127.0.0.1.
 *
 * The decisive setup: an EMPTY allowlist + a default [AgentConsentManager]
 * with foreground=false. In that state the normal gate FAILS CLOSED —
 * pairing a new client returns DENY (→ -32001) and any non-NEVER tool
 * returns DENY (→ -32000). So a *successful* DEVICE-origin call proves the
 * gate was bypassed; the same call over any other origin still fails closed.
 */
class McpLoopbackTrustTest {

    private fun newServer(
        consentManager: AgentConsentManager = AgentConsentManager(),
        pairedClients: Set<String> = emptySet(),
    ): McpServer {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.mcpAllowedClients } returns flowOf(pairedClients)
        coEvery { prefs.addMcpAllowedClient(any()) } returns Unit

        return McpServer(
            context = mockk<Context>(relaxed = true),
            connectionRepository = mockk<ConnectionRepository>(relaxed = true),
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            mailSessionManager = mockk<sh.haven.core.mail.MailSessionManager>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = prefs,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
            auditRecorder = mockk<AgentAuditRecorder>(relaxed = true),
            consentManager = consentManager,
            agentUiCommandBus = sh.haven.core.data.agent.AgentUiCommandBus(),
            transportSelector = mockk<sh.haven.feature.sftp.transport.TransportSelector>(relaxed = true),
            workspaceRepository = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true),
            workspaceLauncher = mockk<sh.haven.app.workspace.WorkspaceLauncher>(relaxed = true),
            tunnelConfigRepository = mockk<sh.haven.core.data.repository.TunnelConfigRepository>(relaxed = true),
            tunnelManager = mockk<sh.haven.core.tunnel.TunnelManager>(relaxed = true),
            terminalSessionRegistry = sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            portKnocker = mockk<sh.haven.core.knock.PortKnocker>(relaxed = true),
            spaSender = mockk<sh.haven.core.spa.SpaSender>(relaxed = true),
            connectionLogRepository = mockk<sh.haven.core.data.repository.ConnectionLogRepository>(relaxed = true),
            servedFileTracker = mockk<sh.haven.core.data.agent.ServedFileTracker>(relaxed = true),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            knownHostDao = mockk(relaxed = true),
            stepCaConfigRepository = mockk<sh.haven.core.data.repository.StepCaConfigRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            ageIdentityRepository = mockk<sh.haven.core.data.repository.AgeIdentityRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            usbIpServer = mockk<sh.haven.core.usb.UsbIpServer>(relaxed = true),
            usbDriveVmManager = mockk<sh.haven.app.usb.UsbDriveVmManager>(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyEnforcer = StandingPolicyEnforcer(mockk(relaxed = true)),
            standingPolicyRepository = mockk(relaxed = true),
            mcpStatusHolder = sh.haven.core.data.agent.McpStatusHolder(),
            mcpTunnelManager = mockk(relaxed = true),
            btSerialSessionManager = mockk(relaxed = true),
            bleSerialSessionManager = mockk(relaxed = true),
            usbSerialSessionManager = mockk(relaxed = true),
            headlessSshExec = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        )
    }

    private fun initBody(clientName: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 0)
            .put("method", "initialize")
            .put("params", JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("clientInfo", JSONObject()
                    .put("name", clientName)
                    .put("version", "1.0")))
            .toString()

    private fun toolsCallBody(name: String, args: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
            .toString()

    @Test
    fun `device-origin initialize auto-trusts a brand-new client when opted in`() {
        // foreground=false + empty allowlist → the normal path would DENY.
        val consentManager = AgentConsentManager()
        val server = newServer(consentManager = consentManager, pairedClients = emptySet())
        server.setTrustLoopbackEnabled(true) // the user's explicit opt-in

        val outcome = server.handleJsonRpc(initBody("fresh-loopback"), requestSessionId = null, origin = McpOrigin.DEVICE)

        val obj = JSONObject(outcome.body)
        assertNull(
            "loopback initialize should succeed without pairing, got error: ${obj.optJSONObject("error")}",
            obj.optJSONObject("error"),
        )
        assertEquals("haven-agent", obj.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
        // No pairing prompt should ever have queued.
        assertTrue("loopback trust must not queue a pairing prompt", consentManager.pending.value.isEmpty())
        // A session is still minted (clientName resolves for the audit trail).
        assertTrue("initialize should still mint a session id", !outcome.responseSessionId.isNullOrBlank())
    }

    @Test
    fun `non-loopback initialize still gates a brand-new client`() {
        val server = newServer(consentManager = AgentConsentManager(), pairedClients = emptySet())
        server.setTrustLoopbackEnabled(true)

        // Same brand-new client, but arriving over a remote (LAN/WG) path.
        val outcome = server.handleJsonRpc(initBody("fresh-remote"), requestSessionId = null, origin = McpOrigin.LAN)

        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected pairing error for a remote new client, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `tunneled origin is never auto-trusted even with loopback trust on`() {
        // The Stage-2 hole: a reverse-tunnel (-R) carrier lands REMOTE
        // traffic on the phone's 127.0.0.1. It must run the full gate no
        // matter what the loopback-trust pref says.
        val server = newServer(consentManager = AgentConsentManager(), pairedClients = emptySet())
        server.setTrustLoopbackEnabled(true)

        val outcome = server.handleJsonRpc(initBody("remote-through-tunnel"), requestSessionId = null, origin = McpOrigin.TUNNELED)

        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected pairing error for a tunneled new client, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }

    @Test
    fun `device origin bypasses per-call consent for a non-NEVER tool when opted in`() {
        // disconnect_profile is non-NEVER; with foreground=false the normal
        // gate returns -32000. Over the trusted device origin it must skip
        // consent and reach the handler (which may then succeed or fail for
        // its own reasons — either way it is NOT a consent denial).
        val server = newServer(consentManager = AgentConsentManager(), pairedClients = emptySet())
        server.setTrustLoopbackEnabled(true)

        val outcome = server.handleJsonRpc(
            toolsCallBody("disconnect_profile", JSONObject().put("profileId", "p1")),
            requestSessionId = null,
            origin = McpOrigin.DEVICE,
        )

        val error = JSONObject(outcome.body).optJSONObject("error")
        val code = error?.optInt("code")
        assertNotEquals("loopback must not be consent-denied (-32000)", -32000, code)
        assertNotEquals("loopback must not be pairing-blocked (-32001)", -32001, code)
    }

    @Test
    fun `loopback trust is OFF by default — device origin runs the full gate`() {
        // No opt-in call: the server's default must fail closed even for a
        // genuine on-device client (#mcp-backbone Stage 2).
        val server = newServer(consentManager = AgentConsentManager(), pairedClients = emptySet())

        val outcome = server.handleJsonRpc(initBody("fresh-loopback"), requestSessionId = null, origin = McpOrigin.DEVICE)

        val error = JSONObject(outcome.body).optJSONObject("error")
            ?: error("expected pairing error with loopback trust at its default, got: ${outcome.body}")
        assertEquals(-32001, error.optInt("code"))
    }
}
