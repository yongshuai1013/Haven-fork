package sh.haven.app.agent

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.data.agent.ConsentDecision
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.data.db.entities.AgentAuditEvent
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
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

private const val TAG = "McpServer"

/** Max time to wait for the user to answer a consent prompt before returning a
 *  retryable error. An interactive human needs time to notice the prompt,
 *  switch to Haven and tap — 8s was unusable in practice. Held below the
 *  socket read timeout (raised in lockstep below) so the connection isn't
 *  dropped mid-wait. If the MCP client gives up sooner, [AgentConsentManager]
 *  clears the orphaned prompt in a finally block so the sheet never sticks. */
private const val CONSENT_WAIT_MS: Long = 55_000L

/** The one MCP resources/read resource: a live snapshot of Haven's own rendered UI.
 *  The file-shaped sibling of the `capture_haven_ui` tool — an agent reads it to
 *  see the current screen without a tool call (VISION.md §1a). (Don't write the
 *  literal slash-star resources glob in a kdoc; it opens a nested block comment.) */
private const val UI_SCREEN_RESOURCE_URI = "ui://haven/screen"

/** Backoff between WireGuard (re)bind attempts when no tunnel is up yet
 *  or the listener dropped (roam re-handshake, tunnel released). */
private const val WG_RETRY_MS: Long = 5_000L

/**
 * Minimal Model Context Protocol (MCP) server for Haven.
 *
 * Binds to **127.0.0.1 only** (loopback) so the server is reachable
 * from local processes (any MCP client running in PRoot, a script,
 * a curl test) but not from the LAN. This is the agent transport
 * that implements Haven's "shared viewport" principle:
 * every observable state and action a human has in the UI should be
 * reachable by an agent through the same underlying ViewModels and
 * repositories.
 *
 * ### Protocol
 *
 * Implements the 2025-06-18 **Streamable HTTP** transport. A single
 * `POST /mcp` endpoint accepts one JSON-RPC 2.0 message and responds
 * with a single JSON body. No SSE, no WebSocket.
 *
 * ### Sessions
 *
 * The server is process-stateful per the streamable-HTTP spec:
 * `initialize` mints a UUID, returned in the `Mcp-Session-Id` response
 * header. The client echoes it on subsequent requests so the server
 * can re-resolve the paired client identity without re-prompting.
 * When the server doesn't recognise a presented session id (because
 * the process restarted, lost its in-memory session map, etc.) it
 * responds with HTTP **404** instead of HTTP 200 + JSON-RPC error
 * — the spec-defined signal that tells the client to re-`initialize`
 * automatically. Without this, Claude Code's MCP transport sees a
 * generic JSON-RPC -32001 and gets wedged until the user tears down
 * its session entirely.
 *
 * Clients that don't send `Mcp-Session-Id` still work via a legacy
 * fallback that consults the last-seen `clientInfo.name` from this
 * process's most recent `initialize`. That fallback is the source of
 * the "won't reconnect after app restart" symptom and exists only
 * for stragglers.
 *
 * Supported methods:
 * - `initialize` — protocol handshake, returns server info + tools capability
 * - `notifications/initialized` — acknowledged, no-op
 * - `tools/list` — returns the available tool definitions
 * - `tools/call` — dispatches to a named tool, passing arguments
 *
 * ### Security model (v1)
 *
 * Loopback-only binding is the entire security story in v1. Anyone
 * who can open a TCP socket to 127.0.0.1 on this device can call
 * every exposed tool. This is acceptable for a read-only v1 on
 * Android because all local processes on Android already have at
 * least as much access to this app's data as they do through normal
 * IPC. Write operations (upload, delete, disconnect, etc.) will
 * require per-action consent before they land in v2.
 */
@Singleton
class McpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: ConnectionRepository,
    private val portForwardRepository: PortForwardRepository,
    private val sshSessionManager: SshSessionManager,
    private val sessionManagerRegistry: SessionManagerRegistry,
    private val reticulumSessionManager: sh.haven.core.reticulum.ReticulumSessionManager,
    private val reticulumForwardServer: sh.haven.core.reticulum.ReticulumForwardServer,
    private val rcloneClient: RcloneClient,
    private val mailSessionManager: sh.haven.core.mail.MailSessionManager,
    private val sftpStreamServer: SftpStreamServer,
    private val hlsStreamServer: HlsStreamServer,
    private val ffmpegExecutor: FfmpegExecutor,
    private val preferencesRepository: UserPreferencesRepository,
    private val terminalFontInstaller: TerminalFontInstaller,
    private val localSessionManager: LocalSessionManager,
    private val auditRecorder: AgentAuditRecorder,
    private val consentManager: AgentConsentManager,
    private val agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    private val transportSelector: sh.haven.feature.sftp.transport.TransportSelector,
    private val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
    private val workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher,
    private val tunnelConfigRepository: sh.haven.core.data.repository.TunnelConfigRepository,
    private val tunnelManager: sh.haven.core.tunnel.TunnelManager,
    private val terminalSessionRegistry: sh.haven.feature.terminal.agent.TerminalSessionRegistry,
    private val portKnocker: sh.haven.core.knock.PortKnocker,
    private val spaSender: sh.haven.core.spa.SpaSender,
    private val connectionLogRepository: sh.haven.core.data.repository.ConnectionLogRepository,
    private val servedFileTracker: sh.haven.core.data.agent.ServedFileTracker,
    private val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
    private val mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
    private val mailWatchManager: sh.haven.app.agent.mailrules.MailWatchManager,
    private val agentActivityHolder: sh.haven.core.data.agent.AgentActivityHolder,
    private val terminalInputQueue: TerminalInputQueue,
    private val prootInstallLogRepository: sh.haven.core.data.repository.ProotInstallLogRepository,
    private val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    private val totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository,
    private val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
    private val desktopSessionRegistry: sh.haven.core.data.desktop.DesktopSessionRegistry,
    private val usbBroker: sh.haven.core.usb.UsbBroker,
    private val usbIpServer: sh.haven.core.usb.UsbIpServer,
    private val usbDriveVmManager: sh.haven.app.usb.UsbDriveVmManager,
    private val presentationManager: sh.haven.core.data.agent.AgentPresentationManager,
    private val mcpStatusHolder: sh.haven.core.data.agent.McpStatusHolder,
    private val mcpTunnelManager: McpTunnelManager,
    // Capture + drive Haven's OWN rendered UI (self-hosting loop, §1a).
    // Registered with the foreground activity by MainActivity.onResume.
    private val havenUiBridge: HavenUiBridge,
    // Tier-3 standing policies: scoped, rate-capped, expiring no-prompt
    // grants consulted at the consent gate (VISION.md "Consent tiers").
    private val standingPolicyEnforcer: StandingPolicyEnforcer,
    private val standingPolicyRepository: sh.haven.core.data.repository.StandingPolicyRepository,
    // Defaulted so the manual McpServer constructions in unit tests (which
    // don't exercise the auth-prompt verbs) compile without it; Hilt always
    // injects the real @Singleton in production.
    private val pendingAuthPromptHolder: sh.haven.core.data.agent.PendingAuthPromptHolder =
        sh.haven.core.data.agent.PendingAuthPromptHolder(),
    // Same as above: defaulted for the manual test constructions; Hilt injects
    // the real @Singleton so this mirrors the same instance ConnectionsViewModel
    // writes the session-manager picker state to.
    private val sessionSelectionHolder: sh.haven.core.data.agent.SessionSelectionHolder =
        sh.haven.core.data.agent.SessionSelectionHolder(),
) : Closeable {

    /**
     * Last `clientInfo.name` we saw on an `initialize` request. Used
     * only as a legacy fallback when the client doesn't send
     * `Mcp-Session-Id`. Preferred path is [sessions]: every
     * non-initialize request that carries a recognised session id
     * resolves to a clientName without consulting this field.
     */
    @Volatile
    private var lastClientHint: String? = null

    /**
     * Session map for the streamable-HTTP transport. `initialize`
     * mints a UUID and stores [Session] here; subsequent requests
     * that present the same id in `Mcp-Session-Id` resolve to the
     * paired clientName. Entries older than [SESSION_TTL_MS] are
     * purged at next initialize to keep the map bounded.
     *
     * Unbounded growth is unlikely in practice (one entry per
     * client-initialize across the lifetime of the foreground process)
     * but the TTL guards against pathological churn.
     */
    private data class Session(val clientName: String, val createdAt: Long)
    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * In-memory mirror of [UserPreferencesRepository.mcpAllowedClients]
     * read once at server start and updated when [handleInitialize]
     * persists a new pairing. Lets every JSON-RPC dispatch check the
     * allowlist without re-collecting the Flow on the hot path. Treat
     * as authoritative within the lifetime of the running server;
     * Settings changes that wipe the allowlist while the server is up
     * are picked up on the next server restart.
     */
    @Volatile
    private var allowedClients: Set<String> = emptySet()

    /**
     * When true (the default), MCP clients arriving on the **loopback**
     * binder (peer address is a loopback address — `adb forward`, an
     * on-device agent) skip both the pairing prompt and per-action
     * consent. This matches the v1 threat model: anyone who can open a
     * socket to 127.0.0.1 is already as trusted as the device itself
     * (see the class kdoc), so prompting there is friction with no
     * security benefit. The LAN and WireGuard binders are the genuinely
     * remote paths and ALWAYS keep the full pairing + consent gate
     * regardless of this flag. Mirrors [UserPreferencesRepository
     * .trustLoopbackMcpClients]; seeded in [start] and updated at
     * runtime via [setTrustLoopbackEnabled]. (#214)
     */
    @Volatile
    private var trustLoopbackEnabled: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Serializes [start] and [stop] so preference-driven toggles that
     * land close together can never leave the server with a half-torn
     * listener or a zombie accept thread.
     */
    private val lifecycleLock = Any()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    private val _endpointUrl = MutableStateFlow<String?>(null)
    val endpointUrl: StateFlow<String?> = _endpointUrl.asStateFlow()

    /**
     * URL the endpoint is reachable at over the active WireGuard tunnel
     * (`http://<wg-interface-ip>:<port>/mcp`), or null when not bound on a
     * WG tunnel. Stable across the device's WiFi/hotspot roams. (#176)
     */
    private val _wireguardEndpointUrl = MutableStateFlow<String?>(null)
    val wireguardEndpointUrl: StateFlow<String?> = _wireguardEndpointUrl.asStateFlow()

    // The WireGuard-shadowed-by-system-VPN warning is published to
    // [mcpStatusHolder] (core/data) so feature modules (Settings) can observe it
    // without depending on :app. See [detectVpnAddressCollision].

    /** The address the WG MCP listener is currently bound to, for collision re-checks. */
    @Volatile private var wireguardBoundAddress: String? = null

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    /** Watches VPN networks so the collision warning updates live as VPNs come/go. */
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** Synthetic profile id under which the MCP listener holds a WG tunnel. */
    private val wireguardProfileId = "mcp-wg-listener"
    /**
     * Synthetic profile id under which the MCP server actively keeps the
     * configured carrier WG tunnel UP (distinct from [wireguardProfileId],
     * which merely binds whatever WG tunnel is live). Holding this refcount
     * is what makes the WG-exposed endpoint survive app restart /
     * re-foreground instead of waiting for some other profile to dial it.
     */
    private val wireguardCarrierProfileId = "mcp-wg-carrier"
    private var wireguardJob: Job? = null
    @Volatile private var wireguardListener: sh.haven.core.tunnel.TunneledServerSocket? = null

    /**
     * URL the endpoint is reachable at on the device's Wi-Fi/LAN address
     * (`http://<lan-ip>:<port>/mcp`), or null when the LAN bind is off or no
     * suitable interface is up. A same-network client can point here
     * directly — no WireGuard, no reverse forward.
     */
    private val _lanEndpointUrl = MutableStateFlow<String?>(null)
    val lanEndpointUrl: StateFlow<String?> = _lanEndpointUrl.asStateFlow()

    private var lanServerSocket: ServerSocket? = null
    private var lanServerThread: Thread? = null

    /**
     * Standard MCP server registration JSON for the Haven endpoint.
     * Null when the server isn't running. Any MCP-aware client can
     * merge this into its own config; the shape is compatible with
     * the common `{ "mcpServers": { "name": { "type", "url" } } }`
     * format most clients accept.
     */
    val mcpServerConfigJson: String?
        get() {
            val url = _endpointUrl.value ?: return null
            return """
                {
                  "mcpServers": {
                    "haven": {
                      "type": "http",
                      "url": "$url"
                    }
                  }
                }
            """.trimIndent()
        }

    private val tools = McpTools(
        context = context,
        connectionRepository = connectionRepository,
        portForwardRepository = portForwardRepository,
        sshSessionManager = sshSessionManager,
        sessionManagerRegistry = sessionManagerRegistry,
        reticulumSessionManager = reticulumSessionManager,
        reticulumForwardServer = reticulumForwardServer,
        rcloneClient = rcloneClient,
        mailSessionManager = mailSessionManager,
        sftpStreamServer = sftpStreamServer,
        hlsStreamServer = hlsStreamServer,
        ffmpegExecutor = ffmpegExecutor,
        preferencesRepository = preferencesRepository,
        terminalFontInstaller = terminalFontInstaller,
        localSessionManager = localSessionManager,
        agentUiCommandBus = agentUiCommandBus,
        transportSelector = transportSelector,
        workspaceRepository = workspaceRepository,
        workspaceLauncher = workspaceLauncher,
        tunnelConfigRepository = tunnelConfigRepository,
        tunnelManager = tunnelManager,
        terminalSessionRegistry = terminalSessionRegistry,
        portKnocker = portKnocker,
        spaSender = spaSender,
        connectionLogRepository = connectionLogRepository,
        servedFileTracker = servedFileTracker,
        syncProfileRepository = syncProfileRepository,
        mailRuleRepository = mailRuleRepository,
        mailWatchManager = mailWatchManager,
        agentActivityHolder = agentActivityHolder,
        terminalInputQueue = terminalInputQueue,
        prootInstallLogRepository = prootInstallLogRepository,
        sshKeyRepository = sshKeyRepository,
        totpSecretRepository = totpSecretRepository,
        ageIdentityRepository = ageIdentityRepository,
        desktopSessionRegistry = desktopSessionRegistry,
        usbBroker = usbBroker,
        usbIpServer = usbIpServer,
        usbDriveVmManager = usbDriveVmManager,
        presentationManager = presentationManager,
        havenUiBridge = havenUiBridge,
        standingPolicyRepository = standingPolicyRepository,
        mcpTunnelManager = mcpTunnelManager,
        mcpStatusHolder = mcpStatusHolder,
        pendingAuthPromptHolder = pendingAuthPromptHolder,
        sessionSelectionHolder = sessionSelectionHolder,
        mcpPortProvider = { port },
    )

    /**
     * Invoke an MCP tool by name, BYPASSING the interactive consent gate. For the
     * Mail-Rules engine only: an enabled rule is the user's standing pre-authorization,
     * and the engine fires while backgrounded (where [tools] consent would fail closed).
     * The tool's own audit still records the call. Throws [McpError] on bad input.
     */
    suspend fun callToolUnconsented(name: String, arguments: org.json.JSONObject): org.json.JSONObject =
        tools.call(name, arguments, clientHint = "mail-rule")

    /**
     * The [ConsentLevel] of tool [name] (null if unknown). The Mail-Rules executor derives
     * a rule action's background-safety posture from this: NEVER → runs in background;
     * non-NEVER → treated as destructive (notify-only / queued until foreground).
     */
    fun toolConsentLevel(name: String): ConsentLevel? = tools.consentFor(name)?.level

    /**
     * Start the server on the first free port in [8730..8739] (a small
     * deterministic range so a reconnecting MCP client can find us
     * again after an app restart). Falls back to an OS-assigned port
     * if all preferred ports are busy.
     *
     * Idempotent: if a healthy instance is already running this is a
     * no-op; if a stale instance is detected (flag set but listener
     * socket closed or accept thread dead) it's torn down and
     * re-created. This matters because the Android process can be
     * suspended for long periods and the server's threads may be
     * killed out from under it while [isRunning] still reads `true`.
     */
    fun start() = synchronized(lifecycleLock) {
        if (isHealthy()) return@synchronized
        if (isRunning) {
            Log.w(TAG, "start() found stale instance, tearing down before rebinding")
            stopLocked()
        }
        val ss = bindLoopback()
        serverSocket = ss
        port = ss.localPort
        isRunning = true
        mcpStatusHolder.setRunning(true)
        _endpointUrl.value = "http://127.0.0.1:$port/mcp"
        // Seed the in-memory allowlist mirror from DataStore. Subsequent
        // pairing approvals append to this on the dispatch thread.
        allowedClients = runBlocking { preferencesRepository.mcpAllowedClients.first() }
        trustLoopbackEnabled = runBlocking { preferencesRepository.trustLoopbackMcpClients.first() }
        Log.i(TAG, "MCP server listening on ${_endpointUrl.value} (paired clients: ${allowedClients.size}, trustLoopback=$trustLoopbackEnabled)")

        serverThread = thread(name = "mcp-http", isDaemon = true) {
            while (isRunning && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    thread(name = "mcp-client", isDaemon = true) {
                        try {
                            handleClient(client)
                        } catch (e: Throwable) {
                            // Last-resort guard so one bad request can
                            // never kill a worker thread silently and
                            // leave the peer socket in CLOSE_WAIT.
                            Log.w(TAG, "worker crashed: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed by stop() — expected on shutdown
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "accept failed: ${e.message}")
                }
            }
            Log.i(TAG, "MCP accept loop exited")
        }

        // Optionally also expose the endpoint on the active WireGuard tunnel.
        if (runBlocking { preferencesRepository.mcpWireguardEnabled.first() }) {
            startWireguardBinderLocked()
        }
        // Optionally also bind the device's Wi-Fi/LAN address for direct
        // same-network reach.
        if (runBlocking { preferencesRepository.mcpLanBindEnabled.first() }) {
            startLanBinderLocked()
        }
    }

    override fun close() = stop()

    /**
     * Enable/disable the LAN-exposed listener at runtime (driven by the
     * `mcpLanBindEnabled` preference). No-op when the server isn't running —
     * [start] picks up the current pref value itself.
     */
    fun setLanBindEnabled(enabled: Boolean) = synchronized(lifecycleLock) {
        if (!isRunning) return@synchronized
        if (enabled) startLanBinderLocked() else stopLanBinderLocked()
    }

    /**
     * Toggle loopback auto-trust at runtime (driven by the
     * `trustLoopbackMcpClients` preference). No lock needed — it's a
     * single volatile flag read on the dispatch path. (#214)
     */
    fun setTrustLoopbackEnabled(enabled: Boolean) {
        trustLoopbackEnabled = enabled
        Log.i(TAG, "MCP loopback auto-trust ${if (enabled) "enabled" else "disabled"}")
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun startLanBinderLocked() {
        if (lanServerThread?.isAlive == true) return
        val boundPort = port
        val lanSs = bindLan(boundPort)
        if (lanSs == null) {
            // No suitable interface up right now (e.g. mobile-only). The
            // HavenApp connectivity observer re-invokes this on the next
            // network change, so this is a soft skip, not an error.
            Log.i(TAG, "MCP LAN bind requested but no Wi-Fi/LAN address is up; will retry on network change")
            return
        }
        lanServerSocket = lanSs
        _lanEndpointUrl.value = "http://${lanSs.inetAddress.hostAddress}:$boundPort/mcp"
        Log.i(TAG, "MCP also listening on LAN ${_lanEndpointUrl.value}")
        lanServerThread = thread(name = "mcp-http-lan", isDaemon = true) {
            while (isRunning && !lanSs.isClosed) {
                try {
                    val client = lanSs.accept()
                    thread(name = "mcp-client-lan", isDaemon = true) {
                        try {
                            handleClient(client)
                        } catch (e: Throwable) {
                            Log.w(TAG, "LAN worker crashed: ${e.message}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: IOException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "LAN accept failed: ${e.message}")
                }
            }
            Log.i(TAG, "MCP LAN accept loop exited")
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun stopLanBinderLocked() {
        try { lanServerSocket?.close() } catch (_: Exception) {}
        lanServerSocket = null
        lanServerThread = null
        _lanEndpointUrl.value = null
    }

    /**
     * Enable/disable the WireGuard-exposed listener at runtime (driven by
     * the `mcpWireguardEnabled` preference). No-op when the server isn't
     * running — [start] picks up the current pref value itself.
     */
    fun setWireguardEnabled(enabled: Boolean) = synchronized(lifecycleLock) {
        if (!isRunning) return@synchronized
        if (enabled) startWireguardBinderLocked() else stopWireguardBinderLocked()
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun startWireguardBinderLocked() {
        if (wireguardJob?.isActive == true) return
        registerVpnWatcherLocked()
        val boundPort = port
        wireguardJob = scope.launch {
            while (isActive) {
                // If a carrier tunnel is configured, actively bring it UP
                // (acquire is get-or-create) so the WG-exposed endpoint comes
                // back on its own after an app restart / re-foreground rather
                // than waiting for some other profile to dial it. Idempotent;
                // re-run each iteration so a dropped carrier is re-established.
                // Zero-config fallback: when no carrier is explicitly set but
                // the user has exactly one WireGuard tunnel config, treat that
                // as the carrier. With several WG configs we stay attach-only
                // (no guessing) until the pref names one.
                val carrierId = preferencesRepository.mcpWireguardTunnelConfigId.first()
                    ?: runCatching {
                        tunnelConfigRepository.getAll()
                            .filter { it.type == "WIREGUARD" }
                            .singleOrNull()?.id
                    }.getOrNull()
                if (!carrierId.isNullOrBlank()) {
                    try {
                        tunnelManager.acquire(carrierId, wireguardCarrierProfileId)
                    } catch (e: Exception) {
                        Log.w(TAG, "MCP WG carrier acquire ($carrierId) failed: ${e.message}")
                    }
                }
                // Bind on whichever WireGuard tunnel is currently up; holding
                // it as a dependent keeps it alive while MCP is bound.
                val tunnel = tunnelManager.acquireFirstWireguard(wireguardProfileId)
                if (tunnel == null) {
                    delay(WG_RETRY_MS)
                    continue
                }
                val ln = try {
                    tunnel.listenTcp(boundPort)
                } catch (e: Exception) {
                    Log.w(TAG, "WG listen on $boundPort failed: ${e.message}")
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                if (ln == null) {
                    tunnelManager.release(wireguardProfileId)
                    delay(WG_RETRY_MS)
                    continue
                }
                wireguardListener = ln
                val wgAddr = tunnel.localAddress()
                _wireguardEndpointUrl.value = wgAddr?.let { "http://$it:$boundPort/mcp" }
                wireguardBoundAddress = wgAddr
                Log.i(TAG, "MCP also listening on WireGuard ${_wireguardEndpointUrl.value ?: ":$boundPort"}")
                refreshWireguardCollision()
                try {
                    while (isActive) {
                        val conn = ln.accept()
                        scope.launch {
                            try {
                                // WireGuard peers are always remote (a WG
                                // tunnel IP) — never loopback-trusted. (#214)
                                handleConnection(conn.inputStream, conn.outputStream, isLoopback = false)
                            } catch (e: Throwable) {
                                Log.w(TAG, "WG worker crashed: ${e.message}")
                            } finally {
                                try { conn.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    // accept() throws when the listener or tunnel closes
                    // (roam re-handshake, profile released the tunnel, or our
                    // own stop closed it) — fall through to re-acquire.
                    Log.i(TAG, "WG accept loop ended: ${e.message}")
                } finally {
                    wireguardListener = null
                    _wireguardEndpointUrl.value = null
                    wireguardBoundAddress = null
                    mcpStatusHolder.setWireguardCollision(null)
                    tunnelManager.release(wireguardProfileId)
                }
                if (isActive) delay(WG_RETRY_MS)
            }
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun stopWireguardBinderLocked() {
        wireguardJob?.cancel()
        wireguardJob = null
        unregisterVpnWatcherLocked()
        wireguardBoundAddress = null
        mcpStatusHolder.setWireguardCollision(null)
        // Close the listener to unblock a pending accept(), then drop our
        // hold on the tunnel.
        try { wireguardListener?.close() } catch (_: Exception) {}
        wireguardListener = null
        _wireguardEndpointUrl.value = null
        scope.launch {
            tunnelManager.release(wireguardProfileId)
            // Drop our active hold on the carrier tunnel too. If another
            // profile still depends on it, it stays up; otherwise it's torn
            // down (we were the only thing keeping it alive).
            tunnelManager.release(wireguardCarrierProfileId)
        }
    }

    /**
     * Best-effort: a warning when an active system VPN (another app's
     * [NetworkCapabilities.TRANSPORT_VPN] network) holds [address] — the same
     * address our userspace WireGuard netstack serves MCP on. The kernel VPN
     * then shadows our listener (peers reach the kernel tun, which RSTs our
     * port), so the WG endpoint is silently unreachable. Needs
     * `ACCESS_NETWORK_STATE` (held); returns null on any lookup failure.
     */
    private fun detectVpnAddressCollision(address: String?): sh.haven.core.data.agent.WgCollisionInfo? {
        if (address.isNullOrBlank()) return null
        val cm = connectivityManager ?: return null
        val target = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return null
        return try {
            val iface = cm.allNetworks.firstNotNullOfOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@firstNotNullOfOrNull null
                val lp = cm.getLinkProperties(net) ?: return@firstNotNullOfOrNull null
                if (lp.linkAddresses.any { it.address == target }) lp.interfaceName ?: "vpn" else null
            } ?: return null
            sh.haven.core.data.agent.WgCollisionInfo(vpnInterface = iface, address = address)
        } catch (e: Exception) {
            Log.w(TAG, "VPN collision check failed: ${e.message}")
            null
        }
    }

    /** Re-evaluate the WG endpoint collision against the currently-bound address. */
    private fun refreshWireguardCollision() {
        val info = detectVpnAddressCollision(wireguardBoundAddress)
        if (info != mcpStatusHolder.wireguardCollision.value) {
            mcpStatusHolder.setWireguardCollision(info)
            if (info != null) {
                Log.w(TAG, "WG MCP carrier shadowed: system VPN (${info.vpnInterface}) holds ${info.address} — endpoint unreachable from peers")
            }
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. Watches VPN networks for live collision updates. */
    private fun registerVpnWatcherLocked() {
        if (vpnNetworkCallback != null) return
        val cm = connectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshWireguardCollision()
            override fun onLost(network: Network) = refreshWireguardCollision()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                refreshWireguardCollision()
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        try {
            cm.registerNetworkCallback(req, cb)
            vpnNetworkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "VPN watcher register failed: ${e.message}")
        }
    }

    /** Must hold [lifecycleLock]. Idempotent. */
    private fun unregisterVpnWatcherLocked() {
        val cb = vpnNetworkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        vpnNetworkCallback = null
    }

    fun stop() = synchronized(lifecycleLock) {
        stopLocked()
    }

    /** Must be called while holding [lifecycleLock]. */
    private fun stopLocked() {
        isRunning = false
        mcpStatusHolder.setRunning(false)
        stopWireguardBinderLocked()
        stopLanBinderLocked()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
        port = 0
        _endpointUrl.value = null
    }

    /**
     * True iff the server is running AND the listener socket is still
     * bound AND the accept thread is still alive. Used by [start] to
     * distinguish a healthy instance from a process-suspend zombie.
     */
    private fun isHealthy(): Boolean {
        if (!isRunning) return false
        val ss = serverSocket ?: return false
        if (ss.isClosed) return false
        val t = serverThread ?: return false
        if (!t.isAlive) return false
        return true
    }

    // --- Socket binding ---

    // Off-device reachability has three layers: McpTunnelManager's SSH
    // reverse tunnel (roaming fallback), the WireGuard-tunnel netstack bind
    // ([startWireguardBinderLocked], #176 — direct reach for a WG peer), and
    // the LAN bind below ([bindLan] — direct reach for a same-network
    // client). All share [handleConnection]'s pairing/consent gate.
    private fun bindLoopback(): ServerSocket {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Try preferred ports first so a client that cached an endpoint
        // across app restarts has a decent chance of finding us again.
        for (p in 8730..8739) {
            try {
                return ServerSocket(p, 10, loopback).apply { reuseAddress = true }
            } catch (_: IOException) {
                // busy, try next
            }
        }
        // All preferred ports busy — let the OS pick one
        return ServerSocket(0, 10, loopback).apply { reuseAddress = true }
    }

    /**
     * Bind a real (kernel) [ServerSocket] on the device's Wi-Fi/LAN IPv4
     * address at [boundPort], so a same-network client reaches the endpoint
     * directly. Returns null when no suitable interface is up (e.g. mobile
     * data only) — the caller treats that as a soft skip and retries on the
     * next connectivity change.
     *
     * We pick a specific interface address rather than binding 0.0.0.0 so we
     * never expose the endpoint on the mobile (rmnet) interface — only on
     * Wi-Fi / Ethernet-style site-local networks the user is deliberately on.
     */
    private fun bindLan(boundPort: Int): ServerSocket? {
        val addr = pickLanAddress() ?: return null
        return try {
            ServerSocket(boundPort, 10, addr).apply { reuseAddress = true }
        } catch (e: IOException) {
            Log.w(TAG, "LAN bind on ${addr.hostAddress}:$boundPort failed: ${e.message}")
            null
        }
    }

    /**
     * The device's preferred Wi-Fi/LAN IPv4 address: an up, non-loopback,
     * site-local IPv4 on a wlan/eth-style interface. Excludes mobile (rmnet)
     * and tunnel (tun/wg/rmnet) interfaces so the LAN bind never lands on a
     * carrier-facing address. Null when none is available.
     */
    private fun pickLanAddress(): InetAddress? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .filterNot { ni ->
                    val n = ni.name.lowercase()
                    n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("wg") ||
                        n.startsWith("dummy") || n.startsWith("p2p")
                }
                .sortedBy { ni -> if (ni.name.lowercase().startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { a -> a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress }
        } catch (e: Exception) {
            Log.w(TAG, "pickLanAddress failed: ${e.message}")
            null
        }
    }

    // --- HTTP + JSON-RPC handling ---

    private fun handleClient(socket: Socket) {
        try {
            // Read timeout. Must exceed CONSENT_WAIT_MS so a request blocked on
            // an interactive consent prompt isn't dropped mid-wait.
            socket.soTimeout = 70_000
            // Loopback peers (the dedicated 127.0.0.1 binder; also covers ::1
            // and 127.0.0.0/8) are local clients. The LAN binder also routes
            // here, but its accepted sockets carry the remote client's LAN
            // address as peer — so same-device-to-LAN-IP still reads as
            // non-loopback and keeps the full gate. (#214)
            val isLoopback = socket.inetAddress?.isLoopbackAddress == true
            socket.use { s -> handleConnection(s.getInputStream(), s.getOutputStream(), isLoopback) }
        } catch (e: Exception) {
            Log.w(TAG, "client handler error: ${e.message}")
        }
    }

    /**
     * Serve one HTTP/JSON-RPC request off a raw stream pair. Shared by the
     * loopback [ServerSocket] path ([handleClient]) and the WireGuard
     * netstack accept loop ([wireguardAcceptLoop]) so both transports run
     * identical request handling + pairing checks (#176).
     */
    private fun handleConnection(input: InputStream, output: OutputStream, isLoopback: Boolean) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            writeError(output, 400, "Bad Request")
            return
        }
        val method = parts[0]
        val path = parts[1]

        // Parse headers
        var contentLength = 0
        var mcpSessionIdHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (name) {
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                    "mcp-session-id" -> mcpSessionIdHeader = value.takeIf { it.isNotEmpty() }
                }
            }
        }

        when {
            method == "POST" && (path == "/mcp" || path == "/") -> {
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""
                val outcome = handleJsonRpc(body, mcpSessionIdHeader, isLoopback)
                if (outcome.httpStatus == 404) {
                    // Streamable-HTTP signal: presented session id is
                    // unknown. Empty body — the client treats this as
                    // "session expired, re-initialize".
                    writeJson(output, 404, "", null)
                } else {
                    writeJson(output, outcome.httpStatus, outcome.body, outcome.responseSessionId)
                }
            }
            method == "GET" && (path == "/mcp" || path == "/") -> {
                // SSE channel for server-initiated messages — not supported
                // in v1. Spec allows 405.
                writeError(output, 405, "Method Not Allowed")
            }
            method == "OPTIONS" -> {
                // CORS preflight — respond permissive for local use
                val headers = buildString {
                    append("HTTP/1.1 204 No Content\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
                    append("Access-Control-Allow-Headers: Content-Type, Mcp-Session-Id, Mcp-Protocol-Version\r\n")
                    append("Content-Length: 0\r\n")
                    append("\r\n")
                }
                output.write(headers.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            else -> writeError(output, 404, "Not Found")
        }
    }

    /**
     * Test-friendly overload — drives the JSON-RPC layer without a
     * session id, exercising the legacy fallback path. Existing tests
     * call this signature. Production traffic goes through the
     * (body, sessionId) overload via [handleClient].
     */
    internal fun handleJsonRpc(body: String): String =
        handleJsonRpc(body, requestSessionId = null).body

    /**
     * Outcome of a JSON-RPC request as seen by the HTTP layer.
     *
     * - [httpStatus] is normally 200; becomes 404 when an unknown
     *   `Mcp-Session-Id` is presented, so the client per spec
     *   re-initialises automatically.
     * - [body] is the JSON-RPC reply body (may be empty for
     *   notifications or 404s).
     * - [responseSessionId] is set by a successful `initialize`; the
     *   HTTP layer echoes it back to the client in the
     *   `Mcp-Session-Id` response header.
     */
    internal data class JsonRpcOutcome(
        val httpStatus: Int,
        val body: String,
        val responseSessionId: String?,
    )

    /**
     * Parse a JSON-RPC 2.0 request and return the response. Visible
     * to tests in the same module so the consent gate, error mapping,
     * and audit-outcome plumbing can be exercised without sockets.
     *
     * [requestSessionId] is the value of the inbound `Mcp-Session-Id`
     * header, or null when the client doesn't send one (legacy path).
     */
    internal fun handleJsonRpc(
        body: String,
        requestSessionId: String?,
        isLoopback: Boolean = false,
    ): JsonRpcOutcome {
        if (body.isBlank()) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: empty body"), null)
        }
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return JsonRpcOutcome(200, jsonRpcError(null, -32700, "Parse error: ${e.message}"), null)
        }
        val id = req.opt("id") // may be null for notifications
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()

        // Notifications (no id) get no response per JSON-RPC spec, but we still
        // need to return an empty 200 for the HTTP layer. Return "" for those.
        val isNotification = !req.has("id")

        // Resolve effective clientName before dispatch. Prefer the
        // session-id path (carries through app restarts as long as the
        // process lives); fall back to the legacy `clientInfo.name`
        // capture only when no session id was presented.
        val sessionClient: String? = requestSessionId?.let { sessions[it]?.clientName }
        if (sessionClient != null) {
            lastClientHint = sessionClient
        } else if (method == "initialize") {
            params.optJSONObject("clientInfo")?.optString("name")
                ?.takeIf { it.isNotEmpty() }
                ?.let { lastClientHint = it }
        }

        val started = System.nanoTime()
        var outcome = AgentAuditEvent.Outcome.OK
        var errorMessage: String? = null
        var resultJson: JSONObject? = null
        var newSessionId: String? = null
        val response = try {
            val result = dispatch(method, params, requestSessionId, isLoopback)
            if (result is JSONObject) resultJson = result
            // A successful `initialize` mints a new session id we'll
            // hand back to the client in the response header. The
            // client echoes it on subsequent requests and we resolve
            // it back to a clientName without re-prompting.
            if (method == "initialize" && result is JSONObject) {
                val clientName = lastClientHint
                if (!clientName.isNullOrBlank()) {
                    purgeExpiredSessions()
                    val sid = UUID.randomUUID().toString()
                    sessions[sid] = Session(clientName, System.currentTimeMillis())
                    newSessionId = sid
                }
            }
            if (isNotification) "" else jsonRpcResult(id, result)
        } catch (e: SessionExpiredError) {
            // Streamable-HTTP signal: the client presented a session
            // id we don't recognise (we restarted; map was cleared).
            // Return 404 with empty body — the spec-defined cue that
            // tells the client to re-`initialize` automatically
            // instead of propagating an opaque -32001 to the user.
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            return JsonRpcOutcome(404, "", null)
        } catch (e: ConsentDeniedError) {
            // User denied via the bottom-sheet prompt, or no foreground
            // activity was available to ask. Audit it as DENIED so the
            // user can tell intent-from-the-agent apart from runtime
            // failure later.
            outcome = AgentAuditEvent.Outcome.DENIED
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "User denied")
        } catch (e: McpError) {
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, e.code, e.message ?: "Error")
        } catch (e: Exception) {
            Log.e(TAG, "dispatch failed for method=$method", e)
            outcome = AgentAuditEvent.Outcome.ERROR
            errorMessage = e.message
            if (isNotification) "" else jsonRpcError(id, -32603, "Internal error: ${e.message}")
        }

        // Record after responding so latency is unaffected. We skip
        // bookkeeping notifications (`notifications/initialized`) —
        // they carry no semantic action and would just clutter the
        // audit view.
        if (method != "notifications/initialized") {
            val toolName = if (method == "tools/call") {
                params.optString("name").takeIf { it.isNotEmpty() }
            } else null
            val rawArgs = if (method == "tools/call") {
                params.optJSONObject("arguments")
            } else if (params.length() > 0) params else null
            // For tools/call, the structured tool output lives under
            // `structuredContent`; the summariser wants that, not the
            // MCP wrapper that holds `content` + `structuredContent`.
            val resultForSummary = if (method == "tools/call") {
                resultJson?.optJSONObject("structuredContent")
            } else resultJson
            val durationMs = (System.nanoTime() - started) / 1_000_000
            auditRecorder.record(
                method = method,
                toolName = toolName,
                rawArgs = rawArgs,
                result = resultForSummary,
                durationMs = durationMs,
                outcome = outcome,
                errorMessage = errorMessage,
                clientHint = lastClientHint,
            )
        }

        return JsonRpcOutcome(200, response, newSessionId)
    }

    /**
     * Drop session entries older than [SESSION_TTL_MS]. Called from
     * the `initialize` success path so the work stays off the hot
     * dispatch path. The map is small in normal use; this is a
     * belt-and-braces guard against unbounded growth under churn.
     */
    private fun purgeExpiredSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.createdAt < cutoff) it.remove()
        }
    }

    /**
     * Dispatch an MCP method to its handler.
     *
     * [requestSessionId] is the inbound `Mcp-Session-Id` header. When
     * present but unrecognised we throw [SessionExpiredError] so the
     * HTTP layer returns 404 and the client per spec re-initialises.
     */
    private fun dispatch(method: String, params: JSONObject, requestSessionId: String?, isLoopback: Boolean): Any? {
        // Loopback auto-trust: a client on the loopback binder is treated
        // as already-paired and consent-bypassed when the pref is on.
        // LAN / WireGuard (isLoopback=false) always run the full gate. (#214)
        val trusted = isLoopback && trustLoopbackEnabled
        // Pairing gate: every method except the pair-or-fail path itself
        // requires the calling client to be in the allowlist. `ping` is
        // intentionally allowed unauthenticated so a client can probe
        // for the server's existence without committing to identifying
        // itself. `notifications/initialized` is a spec-required ack
        // sent right after `initialize` and is exempt so a paired client
        // that just finished its handshake doesn't silently fail it.
        if (!trusted && method != "initialize" && method != "ping" && method != "notifications/initialized") {
            val sessionClient = requestSessionId?.let { sessions[it]?.clientName }
            if (requestSessionId != null && sessionClient == null) {
                // The client sent a session id we don't recognise.
                // Almost always means the server restarted while the
                // client cached its id. Returning -32001 here would
                // wedge the client; SessionExpiredError → HTTP 404
                // tells it to re-initialise.
                throw SessionExpiredError()
            }
            val client = sessionClient ?: lastClientHint
            if (client.isNullOrBlank() || client !in allowedClients) {
                throw McpError(
                    -32001,
                    "MCP client not paired with Haven. Send `initialize` first; " +
                        "if the prompt was denied, open Haven and approve a fresh attempt.",
                )
            }
        }
        return when (method) {
            "initialize" -> handleInitialize(params, trusted)
            "notifications/initialized" -> JSONObject() // ack
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(params, trusted)
            "resources/list" -> handleResourcesList()
            "resources/read" -> handleResourcesRead(params, trusted)
            "ping" -> JSONObject()
            else -> throw McpError(-32601, "Method not found: $method")
        }
    }

    private fun handleInitialize(params: JSONObject, trusted: Boolean): JSONObject {
        val clientProtoVersion = params.optString("protocolVersion", "2025-06-18")
        val clientInfo = params.optJSONObject("clientInfo")
        val clientName = clientInfo?.optString("name")?.takeIf { it.isNotBlank() }
        val clientVersion = clientInfo?.optString("version")?.takeIf { it.isNotBlank() }
        Log.i(TAG, "MCP initialize from name=${clientName ?: "<anonymous>"} v=${clientVersion ?: "?"} protocolVersion=$clientProtoVersion trusted=$trusted")

        // Pairing gate. An empty / unknown name has no way to pair (the
        // user would tap Allow on "MCP client '' wants to connect" with
        // no idea who's behind it), so refuse outright. We keep this even
        // for loopback-trusted clients: a name is cheap to require and
        // keeps the audit trail meaningful.
        if (clientName.isNullOrBlank()) {
            throw McpError(
                -32002,
                "MCP initialize must include clientInfo.name. Anonymous clients " +
                    "cannot be paired and are rejected.",
            )
        }
        // Loopback auto-trust: skip the pairing prompt entirely. We do NOT
        // persist the name to the allowlist — trust here is origin-scoped
        // (loopback binder), not name-scoped, so it must never leak to a
        // later LAN/WireGuard connection that happens to use the same
        // clientInfo.name. The dispatch gate is already skipped for these
        // requests (see [dispatch]). (#214)
        if (trusted) {
            Log.i(TAG, "MCP initialize: '$clientName' on loopback — auto-trusted, no pairing prompt")
            return initializeResult()
        }
        // Read the authoritative allowlist from DataStore on every
        // initialize — the in-memory `allowedClients` mirror is only
        // populated by pairings that happen during this server's
        // lifetime, so a paired-on-disk client whose name predates this
        // server instance would otherwise be re-prompted on every
        // restart. Refresh the cache here so the dispatch hot path
        // doesn't have to round-trip to DataStore.
        val persistedAllowlist = runBlocking { preferencesRepository.mcpAllowedClients.first() }
        if (clientName in persistedAllowlist) {
            Log.i(TAG, "MCP initialize: '$clientName' in persisted allowlist (size=${persistedAllowlist.size}); skipping pairing prompt")
            if (clientName !in allowedClients) {
                allowedClients = allowedClients + clientName
            }
        } else {
            Log.i(TAG, "MCP initialize: '$clientName' is NEW — queueing pairing prompt (persisted allowlist size=${persistedAllowlist.size})")
            val decision = runBlocking {
                consentManager.requestClientPairing(clientName, clientVersion)
            }
            Log.i(TAG, "MCP initialize: pairing decision for '$clientName' = $decision")
            when (decision) {
                ConsentDecision.ALLOW -> {
                    runBlocking { preferencesRepository.addMcpAllowedClient(clientName) }
                    allowedClients = allowedClients + clientName
                    Log.i(TAG, "MCP client '$clientName' paired with Haven (allowlist size=${allowedClients.size})")
                }
                ConsentDecision.DENY -> {
                    throw McpError(
                        -32001,
                        "MCP client '$clientName' is not paired with Haven. " +
                            "The pairing prompt was denied or no Haven activity was visible. " +
                            "Open Haven and retry to surface a fresh pairing prompt.",
                    )
                }
            }
        }
        return initializeResult()
    }

    /** The `initialize` response body, shared by the paired path and the
     *  loopback auto-trust path. */
    private fun initializeResult(): JSONObject = JSONObject().apply {
        put("protocolVersion", "2025-06-18")
        put("serverInfo", JSONObject().apply {
            put("name", "haven-agent")
            put("version", sh.haven.app.BuildConfig.VERSION_NAME)
        })
        put("capabilities", JSONObject().apply {
            put("tools", JSONObject().apply {
                put("listChanged", false)
            })
            // One resource: Haven's own rendered screen (ui://haven/screen).
            put("resources", JSONObject().apply {
                put("subscribe", false)
                put("listChanged", false)
            })
        })
        put("instructions",
            "Haven is a mobile thin-client OS for distributed compute, storage, and presence. " +
                "Use these tools to inspect the user's saved connections, active sessions, and " +
                "cloud storage without disturbing the UI they're looking at.")
    }

    private fun handleToolsList(): JSONObject {
        return JSONObject().apply {
            put("tools", JSONArray().apply {
                tools.definitions().forEach { put(it) }
            })
        }
    }

    private fun handleResourcesList(): JSONObject = JSONObject().apply {
        put("resources", JSONArray().put(JSONObject().apply {
            put("uri", UI_SCREEN_RESOURCE_URI)
            put("name", "Haven UI screen")
            put(
                "description",
                "A live PNG screenshot of Haven's OWN rendered screen — the app UI the user is " +
                    "looking at right now. Read it to see the current Haven UI without a tool call; " +
                    "the file-shaped sibling of the capture_haven_ui tool. FLAG_SECURE (screen " +
                    "security on) and Haven-not-foreground are returned as read errors.",
            )
            put("mimeType", "image/png")
        }))
    }

    private fun handleResourcesRead(params: JSONObject, trusted: Boolean): JSONObject {
        val uri = params.optString("uri")
        if (uri != UI_SCREEN_RESOURCE_URI) {
            throw McpError(-32602, "Unknown resource uri: '$uri'. Call resources/list for what's available.")
        }
        // A screen read can expose credentials, so it carries the SAME gate as
        // the capture_haven_ui tool (ONCE_PER_SESSION) — and shares its memo key
        // (toolName) so a prior allow covers both and the resource can't be used
        // to bypass the tool's consent. Loopback stays auto-trusted.
        // A standing policy covering capture_haven_ui covers the resource
        // read too (same memo key, same gate, same audit).
        val policyAllowed = !trusted && runBlocking {
            standingPolicyEnforcer.permits(lastClientHint, "capture_haven_ui", JSONObject())
        }
        if (!trusted && !policyAllowed) {
            val decision = runBlocking {
                withTimeoutOrNull(CONSENT_WAIT_MS) {
                    consentManager.requestConsent(
                        toolName = "capture_haven_ui",
                        clientHint = lastClientHint,
                        summary = "Let the agent see Haven's own screen",
                        level = ConsentLevel.ONCE_PER_SESSION,
                    )
                }
            }
            when (decision) {
                ConsentDecision.DENY -> throw ConsentDeniedError("capture_haven_ui")
                null -> throw McpError(
                    -32012,
                    "Consent prompt for the Haven UI screen wasn't answered in time — approve it " +
                        "on the device and retry.",
                )
                else -> { /* ALLOW — proceed */ }
            }
        }
        val (b64, mime) = runBlocking { tools.readUiScreenResource() }
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("uri", uri)
                put("mimeType", mime)
                put("blob", b64)
            }))
        }
    }

    private fun handleToolsCall(params: JSONObject, trusted: Boolean): JSONObject {
        val name = params.optString("name", "")
            .ifEmpty { throw McpError(-32602, "Missing tool name") }
        val arguments = params.optJSONObject("arguments") ?: JSONObject()

        // Pre-consent capability gate. Some tools live behind an
        // additional toggle in Settings on top of the global agent
        // endpoint. Failing here means the user doesn't see a consent
        // prompt for a tool that would then error anyway, and the
        // audit log records the denial cleanly. Currently only
        // `serve_file` (raw bytes off-device) is gated this way.
        if (name == "serve_file") {
            val allowed = runBlocking {
                preferencesRepository.agentAllowFileRead.first()
            }
            if (!allowed) {
                throw McpError(
                    -32011,
                    "agent file read is disabled — enable in Settings → Agent endpoint",
                )
            }
        }
        // queue_terminal_input (and its deprecated alias
        // queue_self_message) is a keystroke-injection capability —
        // gated by a separate power-user toggle on top of the
        // endpoint switch, in the same shape as serve_file's gate
        // above. (#161)
        if (name == "queue_terminal_input" || name == "queue_self_message") {
            val allowed = runBlocking {
                preferencesRepository.agentAllowTerminalInputQueue.first()
            }
            if (!allowed) {
                throw McpError(
                    -32011,
                    "queue_terminal_input is disabled — enable in Settings → Agent endpoint → " +
                        "Allow agents to queue terminal input",
                )
            }
        }

        // Look up consent metadata before invoking the handler. Unknown
        // tools fall through to tools.call() which will throw the right
        // error; treating them as NEVER avoids prompting on a typo.
        //
        // Loopback auto-trust (#214) bypasses the per-action consent
        // prompt — but NOT the capability gates above (serve_file /
        // queue_terminal_input), which are explicit feature on/off
        // toggles, not consent, and stay regardless of origin.
        val consent = tools.consentFor(name)
        // Tier-3 standing policy: a user-installed, scoped, rate-capped,
        // expiring grant covers this exact (client, tool, args) — skip the
        // prompt below. Still audited like every call; any non-match (incl.
        // a rate ceiling hit) falls through to the normal prompt. Only
        // evaluated where a prompt would otherwise be needed.
        val policyAllowed = !trusted && consent != null && consent.level != ConsentLevel.NEVER &&
            runBlocking { standingPolicyEnforcer.permits(lastClientHint, name, arguments) }
        if (policyAllowed) {
            Log.i(TAG, "tools/call '$name' allowed by standing policy for '$lastClientHint'")
        }
        if (!trusted && !policyAllowed && consent != null && consent.level != ConsentLevel.NEVER) {
            val summary = try {
                consent.summary(arguments)
            } catch (e: Exception) {
                // A summary builder shouldn't crash the dispatcher; fall
                // back to the tool name so the prompt still renders.
                Log.w(TAG, "summary builder for '$name' threw: ${e.message}")
                name
            }
            // Bound the wait so a prompt the user hasn't answered doesn't block
            // the handler past the socket / client timeout (which surfaced as
            // "operation timed out" / "socket closed"). On timeout, return a
            // retryable error rather than hanging — and never auto-allow.
            // Stable key for this exact operation (sorted args) so an EVERY_CALL
            // approval that the client gave up on can be honoured on the retry of
            // the SAME call without re-prompting (#mcp-timeout).
            val operationKey = runCatching {
                arguments.keys().asSequence().sorted()
                    .joinToString("") { k -> "$k=${arguments.opt(k)}" }
            }.getOrDefault(arguments.toString())
            val decision = runBlocking {
                withTimeoutOrNull(CONSENT_WAIT_MS) {
                    consentManager.requestConsent(
                        toolName = name,
                        clientHint = lastClientHint,
                        summary = summary,
                        level = consent.level,
                        operationKey = operationKey,
                    )
                }
            }
            when (decision) {
                ConsentDecision.DENY -> throw ConsentDeniedError(name)
                null -> throw McpError(
                    -32012,
                    "Consent prompt for '$name' wasn't answered in time — approve it on the device " +
                        "and retry. (Once approved for this session, further calls won't prompt.)",
                )
                else -> { /* ALLOW — proceed */ }
            }
        }
        mcpStatusHolder.callStarted(name)
        val content = try {
            runBlocking { tools.call(name, arguments, lastClientHint) }
        } catch (e: McpError) {
            mcpStatusHolder.callFinished(name, e.message)
            throw e
        } catch (e: Exception) {
            mcpStatusHolder.callFinished(name, e.message)
            Log.e(TAG, "tool '$name' threw", e)
            throw McpError(-32603, "Tool failed: ${e.message}")
        }
        mcpStatusHolder.callFinished(name)
        // A proxied guest-MCP tool (McpTools aggregation) returns the guest
        // server's own MCP content array under the reserved key __mcpContent.
        // Forward it verbatim so the guest's text/image blocks reach the
        // agent unchanged, instead of being re-serialised as nested JSON text.
        content.optJSONArray("__mcpContent")?.let { passthrough ->
            content.remove("__mcpContent")
            return JSONObject().apply {
                put("content", passthrough)
                if (content.length() > 0) put("structuredContent", content)
            }
        }
        // A tool can return an inline image by setting the reserved keys
        // __imageBase64 + __mimeType (see McpTools.captureDesktop). Lift
        // them into a proper MCP `image` content block so the agent sees
        // the picture directly over the existing channel — no second port,
        // no file download — and strip them from the text echo /
        // structuredContent so the giant base64 blob isn't duplicated.
        val imageB64 = content.optString("__imageBase64", null)
        val imageMime = content.optString("__mimeType", null)
        if (imageB64 != null) {
            content.remove("__imageBase64")
            content.remove("__mimeType")
        }
        return JSONObject().apply {
            put("content", JSONArray().apply {
                if (imageB64 != null) {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("data", imageB64)
                        put("mimeType", imageMime ?: "image/png")
                    })
                }
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", content.toString(2))
                })
            })
            put("structuredContent", content)
        }
    }

    // --- JSON-RPC response builders ---

    private fun jsonRpcResult(id: Any?, result: Any?): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id)
        obj.put("result", result ?: JSONObject.NULL)
        return obj.toString()
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        obj.put("error", JSONObject().apply {
            put("code", code)
            put("message", message)
        })
        return obj.toString()
    }

    // --- HTTP response helpers ---

    private fun writeJson(
        out: OutputStream,
        status: Int,
        body: String,
        sessionId: String? = null,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusLine = when (status) {
            200 -> "200 OK"
            204 -> "204 No Content"
            404 -> "404 Not Found"
            else -> "$status OK"
        }
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            // Expose Mcp-Session-Id so browser-based clients (none in
            // v1, but cheap insurance) can read it from the response.
            append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n")
            append("Cache-Control: no-store\r\n")
            if (sessionId != null) append("Mcp-Session-Id: $sessionId\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun writeError(out: OutputStream, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val statusLine = "$status $text"
        val headers = buildString {
            append("HTTP/1.1 $statusLine\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}

/** Session entries older than this are evicted on the next initialize. */
private const val SESSION_TTL_MS: Long = 24L * 60L * 60L * 1000L

/** Lightweight error type carrying a JSON-RPC error code. */
open class McpError(val code: Int, message: String) : RuntimeException(message)

/**
 * Raised by the dispatcher when the user denies a consent prompt (or no
 * foreground activity is available to render one). Audit is recorded
 * with [AgentAuditEvent.Outcome.DENIED] so the dashboard can distinguish
 * "the agent tried and we said no" from "the agent tried and it broke."
 */
class ConsentDeniedError(toolName: String) :
    McpError(-32000, "User denied: $toolName")

/**
 * Raised by [McpServer.dispatch] when a non-initialize request
 * presents an `Mcp-Session-Id` the server doesn't recognise. The HTTP
 * layer maps this to **404**, which is the streamable-HTTP-spec signal
 * that tells the client to re-`initialize` and retry — fixing the
 * "won't reconnect after Haven restart without dropping the Claude
 * Code session" wedge.
 *
 * The error message is informational only; clients react to the 404
 * status, not the body.
 */
class SessionExpiredError :
    McpError(-32001, "MCP session expired; re-initialize")
