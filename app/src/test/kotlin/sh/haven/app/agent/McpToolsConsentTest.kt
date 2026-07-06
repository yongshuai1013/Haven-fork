package sh.haven.app.agent

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.ConnectionProfile
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
 * Pin the consent metadata + summary builders for every registered MCP
 * tool. The audit log relies on consent-gating decisions made *before*
 * the handler runs, so a regression here (e.g. dropping a level back to
 * NEVER on a destructive tool, or breaking the summary template) would
 * silently weaken the wheel-stays-with-the-user invariant from
 * VISION.md §85.
 *
 * We construct a real [McpTools] with relaxed mocks so the registry is
 * the actual production registry — same code path the dispatcher
 * consults via `consentFor(name)`.
 */
class McpToolsConsentTest {

    private fun newTools(
        preferencesRepository: UserPreferencesRepository = mockk(relaxed = true),
    ): McpTools {
        val connectionRepository = mockk<ConnectionRepository>(relaxed = true)
        // profileLabel(...) hits this; return a stable label so the
        // summary templates that interpolate it are predictable.
        coEvery { connectionRepository.getById(any()) } returns
            ConnectionProfile(
                id = "p1",
                label = "test-host",
                host = "10.0.0.1",
                username = "u",
            )
        return McpTools(
            context = mockk<Context>(relaxed = true),
            connectionRepository = connectionRepository,
            portForwardRepository = mockk<PortForwardRepository>(relaxed = true),
            sshSessionManager = mockk<SshSessionManager>(relaxed = true),
            sessionManagerRegistry = mockk<SessionManagerRegistry>(relaxed = true),
            rcloneClient = mockk<RcloneClient>(relaxed = true),
            mailSessionManager = mockk<sh.haven.core.mail.MailSessionManager>(relaxed = true),
            sftpStreamServer = mockk<SftpStreamServer>(relaxed = true),
            hlsStreamServer = mockk<HlsStreamServer>(relaxed = true),
            ffmpegExecutor = mockk<FfmpegExecutor>(relaxed = true),
            preferencesRepository = preferencesRepository,
            terminalFontInstaller = mockk<TerminalFontInstaller>(relaxed = true),
            localSessionManager = mockk<LocalSessionManager>(relaxed = true),
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
            servedFileTracker = sh.haven.core.data.agent.ServedFileTracker(),
            syncProfileRepository = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true),
            terminalInputQueue = mockk<TerminalInputQueue>(relaxed = true),
            prootInstallLogRepository = mockk<sh.haven.core.data.repository.ProotInstallLogRepository>(relaxed = true),
            sshKeyRepository = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true),
            totpSecretRepository = mockk<sh.haven.core.data.repository.TotpSecretRepository>(relaxed = true),
            ageIdentityRepository = mockk<sh.haven.core.data.repository.AgeIdentityRepository>(relaxed = true),
            desktopSessionRegistry = mockk<sh.haven.core.data.desktop.DesktopSessionRegistry>(relaxed = true),
            usbBroker = mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            usbIpServer = mockk<sh.haven.core.usb.UsbIpServer>(relaxed = true),
            usbDriveVmManager = mockk<sh.haven.app.usb.UsbDriveVmManager>(relaxed = true),
            presentationManager = sh.haven.core.data.agent.AgentPresentationManager(),
            havenUiBridge = mockk(relaxed = true),
            standingPolicyRepository = mockk(relaxed = true),
            mcpTunnelManager = mockk(relaxed = true),
            mcpStatusHolder = mockk(relaxed = true),
            reticulumSessionManager = mockk(relaxed = true),
            reticulumForwardServer = mockk(relaxed = true),
            mailRuleRepository = mockk(relaxed = true),
            mailWatchManager = mockk(relaxed = true),
            agentActivityHolder = mockk(relaxed = true),
        )
    }

    @Test
    fun `read-only and tap-equivalent tools are NEVER`() {
        val tools = newTools()
        for (name in listOf(
            "get_app_info",
            "list_connections",
            "list_sessions",
            "list_rclone_remotes",
            // Unified backend-agnostic listing (#127). The two
            // per-backend tools below are deprecated aliases pointing
            // at this one.
            "list_directory",
            "list_rclone_directory",
            "list_sftp_directory",
            "read_terminal_scrollback",
            // Navigation + dialog-staging: open a screen at a path or
            // stage a dialog with prefilled args. The destructive action
            // (transcode, in the convert case) still requires the user
            // to tap Convert in the dialog. Tap-equivalent, no consent.
            "navigate_sftp_browser",
            "focus_terminal_session",
            "open_convert_dialog_with_args",
            // Listing the user's saved workspaces is read-only and
            // surfaces no secrets — no consent.
            "list_workspaces",
            // Enumerating attached USB devices is read-only; descriptor
            // strings stay hidden until permission is separately granted.
            "list_usb_devices",
            // raise_notification posts to a dedicated, user-mutable
            // channel and the result is deliberately user-visible — same
            // shape as present_media: agent→user surface, no prompt.
            "raise_notification",
            // Enumerating installed guest apps reads .desktop files; no
            // secrets, no side effects — read-only, no prompt.
            "list_guest_apps",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, c.level)
        }
    }

    @Test
    fun `connection lifecycle tools are ONCE_PER_SESSION`() {
        val tools = newTools()
        for (name in listOf(
            "disconnect_profile",
            "add_port_forward",
            "remove_port_forward",
            // compose_workspace can launch many sessions in one go;
            // gate it once per (client, tool) so the user grants the
            // bundle and isn't re-prompted per-item underneath.
            "compose_workspace",
            // send_terminal_input types into a live terminal; gated once per
            // (client, tool) so an agent isn't re-prompted on every keystroke
            // batch. Shipped behaviour since the queue_terminal_input split;
            // this assertion was masked while the suite didn't compile.
            "send_terminal_input",
            // request_usb_permission pops the system grant dialog + opens
            // the device; gate once per (client, tool) so the agent isn't
            // re-prompted each time it reopens the same device.
            "request_usb_permission",
            // usb_attach_to_guest opens the device + exposes it to the guest;
            // a session-scoped grant matches the permission grant above.
            "usb_attach_to_guest",
            // test_spa sends a single SPA packet; one grant per (client, tool)
            // mirrors test_port_knock.
            "test_spa",
            // read_logcat surfaces system-wide PII; gate per (client, tool)
            // so a tester session grants once and isn't re-prompted across
            // the before/after captures that a single review needs.
            "read_logcat",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name should re-prompt only once per (client, tool)",
                ConsentLevel.ONCE_PER_SESSION,
                c.level,
            )
        }
    }

    @Test
    fun `destructive write tools are EVERY_CALL`() {
        val tools = newTools()
        for (name in listOf(
            // Unified backend-agnostic write/delete (#127).
            "upload_file",
            "delete_file",
            // Deprecated SSH-only originals — kept for backward compat,
            // still EVERY_CALL.
            "upload_file_to_sftp",
            "delete_sftp_file",
            "convert_file",
            "set_terminal_font_from_url",
            "install_apk_from_url",
            "enable_wireless_adb",
            // Raw USB I/O to a device — re-confirm every transfer.
            "usb_control_transfer",
            "usb_bulk_transfer",
            // set_spa writes SPA key material onto a profile.
            "set_spa",
        )) {
            val c = tools.consentFor(name)
                ?: error("$name not registered")
            assertEquals(
                "$name must prompt on every call",
                ConsentLevel.EVERY_CALL,
                c.level,
            )
        }
    }

    @Test
    fun `deprecated tool descriptions point at the unified replacement`() {
        // Pin the deprecation breadcrumbs so a future renamer doesn't
        // silently drop the migration hint. Agents that still use the
        // old tool names should be nudged toward the new ones via the
        // tools/list response.
        val tools = newTools()
        val defs = tools.definitions().associateBy { it.optString("name") }
        for ((deprecated, replacement) in mapOf(
            "list_sftp_directory" to "list_directory",
            "list_rclone_directory" to "list_directory",
            "upload_file_to_sftp" to "upload_file",
            "delete_sftp_file" to "delete_file",
        )) {
            val desc = defs[deprecated]?.optString("description")
                ?: error("$deprecated missing")
            assertTrue(
                "$deprecated description must mention DEPRECATED, got: $desc",
                desc.contains("DEPRECATED", ignoreCase = true),
            )
            assertTrue(
                "$deprecated description must point at $replacement, got: $desc",
                desc.contains(replacement),
            )
        }
    }

    @Test
    fun `unknown tool returns null`() {
        assertNull(newTools().consentFor("does_not_exist"))
    }

    @Test
    fun `disconnect_profile summary names the profile label`() {
        val tools = newTools()
        val c = tools.consentFor("disconnect_profile")!!
        val summary = c.summary(JSONObject().put("profileId", "p1"))
        assertTrue(
            "summary must mention the human label, got: $summary",
            summary.contains("test-host"),
        )
        assertTrue(summary.contains("Disconnect"))
    }

    @Test
    fun `add_port_forward summary describes the rule`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "LOCAL")
            .put("bindPort", 8443)
            .put("targetHost", "10.0.0.5")
            .put("targetPort", 443))
        assertTrue("got: $summary", summary.contains("LOCAL"))
        assertTrue("got: $summary", summary.contains("8443"))
        assertTrue("got: $summary", summary.contains("10.0.0.5:443"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `add_port_forward summary handles DYNAMIC without target host`() {
        val tools = newTools()
        val c = tools.consentFor("add_port_forward")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("type", "DYNAMIC")
            .put("bindPort", 1080))
        // No targetHost on DYNAMIC, but the builder must not crash and
        // must still mention the bind port.
        assertTrue("got: $summary", summary.contains("DYNAMIC"))
        assertTrue("got: $summary", summary.contains("1080"))
        assertTrue("got: $summary", summary.contains("(SOCKS)"))
    }

    @Test
    fun `delete_sftp_file summary shows path and host`() {
        val tools = newTools()
        val c = tools.consentFor("delete_sftp_file")!!
        val summary = c.summary(JSONObject()
            .put("profileId", "p1")
            .put("path", "/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("/var/log/build.log"))
        assertTrue("got: $summary", summary.contains("test-host"))
    }

    @Test
    fun `send_terminal_input summary shows literal payload (truncated)`() {
        val tools = newTools()
        val c = tools.consentFor("send_terminal_input")!!
        val short = c.summary(JSONObject()
            .put("sessionId", "s1")
            .put("text", "ls -la\n"))
        assertTrue("must show the typed text literally, got: $short",
            short.contains("ls -la"))
        // Newline is rendered as \n so the user sees it. Otherwise an
        // input ending with Enter looks the same as one that doesn't.
        assertTrue("newline must be visible in the summary, got: $short",
            short.contains("\\n"))

        // Long input is truncated so the prompt stays readable.
        val long = "x".repeat(200)
        val summary = c.summary(JSONObject().put("sessionId", "s1").put("text", long))
        assertTrue("long input must be truncated with ellipsis, got: ${summary.length} chars",
            summary.length < 200)
        assertTrue("got: $summary", summary.contains("…"))
    }

    @Test
    fun `convert_file summary names the codecs`() {
        val tools = newTools()
        val c = tools.consentFor("convert_file")!!
        val summary = c.summary(JSONObject()
            .put("sourceUrl", "http://127.0.0.1:8080/in.mkv")
            .put("container", "mp4")
            .put("videoEncoder", "libx264")
            .put("audioEncoder", "aac"))
        assertTrue("got: $summary", summary.contains("mp4"))
        assertTrue("got: $summary", summary.contains("libx264"))
        assertTrue("got: $summary", summary.contains("aac"))
    }

    @Test
    fun `summary builder is robust to missing optional args`() {
        val tools = newTools()
        // upload_file_to_sftp summary opens File(localPath) — passing a
        // non-existent path must yield a sane string, not a crash.
        val upload = tools.consentFor("upload_file_to_sftp")!!
        val s = upload.summary(JSONObject()
            .put("profileId", "p1")
            .put("localPath", "/no/such/file")
            .put("remotePath", "/dest"))
        assertTrue("got: $s", s.contains("/dest"))
        assertTrue("got: $s", s.contains("test-host"))
        assertFalse(
            "must not crash on missing file — should report unknown size",
            s.isBlank(),
        )
    }

    @Test
    fun `read_logcat summary names the scope`() {
        val tools = newTools()
        val c = tools.consentFor("read_logcat")!!
        val sysWide = c.summary(JSONObject())
        assertTrue("got: $sysWide", sysWide.contains("system-wide"))
        val perPkg = c.summary(JSONObject().put("packageName", "com.example.app"))
        assertTrue("got: $perPkg", perPkg.contains("com.example.app"))
        val perPid = c.summary(JSONObject().put("pid", 4242))
        assertTrue("got: $perPid", perPid.contains("4242"))
    }

    @Test
    fun `install_apk_from_url summary surfaces the source as a warning`() {
        val tools = newTools()
        val c = tools.consentFor("install_apk_from_url")!!
        val s = c.summary(JSONObject()
            .put("url", "https://example.com/app.apk"))
        // The summary needs to make the trust call obvious — APK install
        // is the highest-risk verb in the registry.
        assertTrue("got: $s", s.contains("APK") || s.contains("install"))
        assertTrue("got: $s", s.contains("example.com") || s.contains("trusted"))
    }

    // --- Declarative capability gate (#mcp-backbone Stage 5) ---

    @Test
    fun `serve_file capability denial is null when file read is enabled`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowFileRead } returns flowOf(true)
        assertNull(runBlocking { newTools(prefs).capabilityDenial("serve_file") })
    }

    @Test
    fun `serve_file is denied with a Settings-pointing message when file read is off`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowFileRead } returns flowOf(false)
        val msg = runBlocking { newTools(prefs).capabilityDenial("serve_file") }
        assertNotNull("serve_file must be gated when the toggle is off", msg)
        assertTrue("got: $msg", msg!!.contains("file read is disabled"))
    }

    @Test
    fun `queue_terminal_input and its deprecated alias share the terminal-input capability`() {
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        every { prefs.agentAllowTerminalInputQueue } returns flowOf(false)
        for (name in listOf("queue_terminal_input", "queue_self_message")) {
            val msg = runBlocking { newTools(prefs).capabilityDenial(name) }
            assertNotNull("$name should be gated", msg)
            assertTrue("got: $msg", msg!!.contains("queue_terminal_input is disabled"))
        }
    }

    @Test
    fun `an ungated tool returns no denial without ever reading a pref`() {
        // No pref stubs on the relaxed mock: capabilityDenial must short-
        // circuit on the null capability BEFORE touching preferences, which
        // is why gating every tool through it doesn't perturb other tests.
        assertNull(runBlocking { newTools().capabilityDenial("list_connections") })
    }

    // --- Key-store provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `key-store tools are aggregated into the registry with their consent levels intact`() {
        // The TOTP + age tools now live in KeyStoreToolProvider, not McpTools'
        // own map. This asserts the aggregation surfaces them unchanged —
        // same names, same consent levels, still in definitions().
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        val readOnly = listOf("list_totp_secrets", "list_age_identities")
        val everyCall = listOf(
            "create_totp_secret", "delete_totp_secret",
            "create_age_identity", "encrypt_file", "decrypt_file",
        )
        for (name in readOnly) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        for (name in everyCall) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor(name)!!.level)
        }
    }

    @Test
    fun `a provider tool dispatches through the aggregated call path`() {
        // Proves the provider's handler is reachable via McpTools.call — the
        // same entry point the transport uses — not just registered.
        val prefs = mockk<UserPreferencesRepository>(relaxed = true)
        val tools = newTools(prefs)
        val out = runBlocking { tools.call("list_totp_secrets", JSONObject()) }
        // Relaxed TotpSecretRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- Tunnel provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `tunnel-config tools are aggregated with their consent levels intact`() {
        // list_tunnels/list_live_tunnels/create_tunnel/delete_tunnel moved to
        // TunnelToolProvider; set_profile_routing stayed in McpTools (it edits
        // a connection profile, not a tunnel config). Assert both facts.
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        for (name in listOf("list_tunnels", "list_live_tunnels")) {
            assertTrue("$name missing from definitions()", name in definitionNames)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        assertEquals("create_tunnel must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("create_tunnel")!!.level)
        assertEquals("delete_tunnel must be ONCE_PER_SESSION", ConsentLevel.ONCE_PER_SESSION, tools.consentFor("delete_tunnel")!!.level)
        // set_profile_routing is NOT in the tunnel provider — still registered by McpTools.
        assertTrue("set_profile_routing must remain registered", "set_profile_routing" in definitionNames)
        assertEquals(ConsentLevel.ONCE_PER_SESSION, tools.consentFor("set_profile_routing")!!.level)
    }

    @Test
    fun `list_tunnels dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_tunnels", JSONObject()) }
        // Relaxed TunnelConfigRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- SSH-key provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `ssh-key tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val definitionNames = tools.definitions().map { it.getString("name") }.toSet()
        assertTrue("list_ssh_keys missing", "list_ssh_keys" in definitionNames)
        assertEquals("list_ssh_keys must be NEVER", ConsentLevel.NEVER, tools.consentFor("list_ssh_keys")!!.level)
        assertEquals("import_ssh_key must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("import_ssh_key")!!.level)
        assertEquals("delete_ssh_key must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor("delete_ssh_key")!!.level)
        assertEquals("set_ssh_key_option must be ONCE_PER_SESSION", ConsentLevel.ONCE_PER_SESSION, tools.consentFor("set_ssh_key_option")!!.level)
    }

    @Test
    fun `list_ssh_keys dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_ssh_keys", JSONObject()) }
        // Relaxed SshKeyRepository.getAll() returns an empty list.
        assertEquals(0, out.getInt("count"))
    }

    // --- Rclone provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `rclone tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // A representative slice across the domain's three impl clusters:
        // remote-config CRUD, sync jobs/stats, and saved sync profiles.
        val readOnly = listOf(
            "list_rclone_remotes", "list_rclone_directory", "list_rclone_provider_options",
            "get_rclone_sync_status", "get_rclone_stats", "list_saved_sync_profiles",
        )
        val everyCall = listOf(
            "start_rclone_sync", "save_sync_profile",
            // Destructive → EVERY_CALL (the saved config / remote is gone after).
            "delete_sync_profile", "delete_rclone_remote",
        )
        for (name in readOnly) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be NEVER", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        for (name in everyCall) {
            assertTrue("$name missing from definitions()", name in names)
            assertEquals("$name must be EVERY_CALL", ConsentLevel.EVERY_CALL, tools.consentFor(name)!!.level)
        }
        // All 15 rclone tools present.
        assertTrue("expected all 15 rclone tools", names.count { it.contains("rclone") || it.endsWith("sync_profile") || it.endsWith("sync_profiles") } >= 15)
    }

    @Test
    fun `list_rclone_remotes dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_rclone_remotes", JSONObject()) }
        // Relaxed RcloneClient.listRemotes() returns an empty list → count 0.
        assertEquals(0, out.getInt("count"))
    }

    // --- USB provider extraction (#mcp-backbone Stage 5, Layer E) ---

    @Test
    fun `usb tools are aggregated with their consent levels intact`() {
        val tools = newTools()
        val names = tools.definitions().map { it.getString("name") }.toSet()
        // list_usb_devices is read-only; the transfer/attach/export/drive verbs
        // gate. A representative slice across the broker / usbip / drive-VM
        // clusters.
        assertTrue("list_usb_devices missing", "list_usb_devices" in names)
        assertEquals("list_usb_devices must be NEVER", ConsentLevel.NEVER, tools.consentFor("list_usb_devices")!!.level)
        for (name in listOf(
            "request_usb_permission", "usb_control_transfer", "usb_bulk_transfer",
            "usb_attach_to_guest", "start_usbip_export", "open_usb_drive",
            "unlock_usb_drive_partition", "delete_usb_appliance",
        )) {
            assertTrue("$name missing from definitions()", name in names)
            assertNotEquals("$name should gate (not NEVER)", ConsentLevel.NEVER, tools.consentFor(name)!!.level)
        }
        // list_bridges (the Bridges registry) stays in McpTools, not the USB provider.
        assertTrue("list_bridges must remain registered", "list_bridges" in names)
    }

    @Test
    fun `list_usb_devices dispatches through the aggregated call path`() {
        val tools = newTools(mockk(relaxed = true))
        val out = runBlocking { tools.call("list_usb_devices", JSONObject()) }
        // Relaxed UsbBroker.listDevices() returns an empty list → count 0.
        assertEquals(0, out.getInt("count"))
    }
}
