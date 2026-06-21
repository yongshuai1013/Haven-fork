package sh.haven.app.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.local.GuestServiceManager
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshKeyExporter
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "McpTunnelManager"

/**
 * Brings up a dedicated, headless SSH reverse tunnel that forwards the
 * device-local MCP HTTP listener (`127.0.0.1:<port>`) back to the
 * laptop running the MCP client, so the endpoint stays reachable as the
 * device's WiFi/hotspot address roams and across app restarts.
 *
 * **Always-on for a configured endpoint.** This `-R` is the managed
 * transport that makes the device's MCP reachable on a *remote SSH endpoint
 * host* (the designated `mcpTunnelEndpointProfileId`). It runs independent of
 * WireGuard: WG exposes MCP on the device's own WG address (for WG-network
 * peers + on-device loopback), which gives a separate SSH host nothing — so
 * the two are parallel transports to different audiences, not alternatives.
 * (Earlier builds stood this `-R` down whenever any WG tunnel was live, which
 * wrongly killed MCP on a remote endpoint host the moment WG came up.) Rebind
 * churn on roam/reconnect is owned by the watchdog in [launchTunnel] (liveness
 * probe + self-healing reconnect), not by deferring to WG.
 *
 * This is decoupled from the user's interactive "near" terminal session
 * (which dies on every app restart and didn't reliably re-establish its
 * `-R`). The tunnel:
 *  - rides its own [SshSessionManager] session marked `headless` (no
 *    shell channel ever opened — see [SshSessionManager.registerSession]),
 *  - uses an unlimited auto-reconnect policy that also fires on network
 *    change, so it self-heals after roams and restarts,
 *  - installs the `-R` forward as a **critical** port forward, so a
 *    stale server-side bind surfaces as a failed (re)connect that retries
 *    rather than a silently forward-less "connected" session
 *    (`ExitOnForwardFailure` semantics — see
 *    [SshSessionManager.applyPortForwards]).
 *
 * Lifecycle is bound to the MCP endpoint toggle in `HavenApp`: [start]
 * on enable, [stop] on disable.
 *
 * Limitation: a headless tunnel can only authenticate non-interactively.
 * Endpoint profiles that require a biometric/passphrase unlock or a FIDO2
 * hardware tap are skipped (logged), since there's no UI to drive the
 * prompt from the foreground service.
 */
@Singleton
class McpTunnelManager @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val sshKeyRepository: SshKeyRepository,
    private val sshSessionManager: SshSessionManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
    private val guestServiceManager: GuestServiceManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var job: Job? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var currentPort: Int = 0

    /**
     * Establish (or re-establish) the reverse tunnel forwarding
     * `127.0.0.1:[mcpPort]` on the laptop back to the device. Idempotent:
     * a no-op if already running for the same port; restarts if the port
     * changed (e.g. the MCP server fell back from 8730 to 8731).
     */
    fun start(mcpPort: Int) = synchronized(lock) {
        if (job?.isActive == true && currentPort == mcpPort) return
        stopLocked()
        currentPort = mcpPort
        launchTunnel(mcpPort)
    }

    private fun launchTunnel(mcpPort: Int) {
        job = scope.launch {
            // Supervisory loop. The SSH `-R` is the unconditional managed
            // transport for a configured endpoint — it runs independent of
            // WireGuard (WG serves the device's own WG address; it gives a
            // remote SSH endpoint host nothing). Rebind churn is owned by the
            // watchdog below, not by deferring to WG.
            while (isActive) {
                // Connect phase: retry with backoff until the tunnel + critical
                // forward are up, or the config is unusable (FATAL).
                var delayMs = INITIAL_RETRY_MS
                var established = false
                while (isActive && !established) {
                    when (establish(mcpPort)) {
                        Outcome.ESTABLISHED -> established = true
                        Outcome.FATAL -> return@launch // misconfig — retrying won't help
                        Outcome.RETRY -> {
                            delay(delayMs)
                            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_MS)
                        }
                    }
                }
                if (!established) continue // cancelled

                // Watchdog phase: a headless session has no shell channel, so the
                // usual channel-exit reconnect trigger never fires for it. Network
                // roams are caught by NetworkMonitor, but a silent keepalive
                // timeout / server-side close would otherwise go unnoticed. Poll
                // the session and kick a reconnect when the transport has dropped.
                //
                // TCP-liveness (client.isConnected) is necessary but not sufficient:
                // a *wedged* reverse forward — the endpoint sshd still LISTENs on the
                // bind port but its forwarded channel is stuck (send-q backed up after
                // a roam/restart) — leaves the session looking CONNECTED while MCP
                // clients hang with no response. Keepalive can't see it (the transport
                // is up). So additionally probe the forward end-to-end from the
                // endpoint and force a reconnect (which rebinds, self-healing any stale
                // holder) when it stops responding.
                val sid = sessionId ?: continue
                var probeFails = 0
                var watching = true
                while (isActive && watching) {
                    delay(HEALTH_CHECK_MS)
                    val s = sshSessionManager.getSession(sid)
                    if (s == null) {
                        // Session evaporated — fall back to the outer loop to
                        // re-establish rather than giving up (the endpoint stays
                        // enabled, so the tunnel should self-heal).
                        watching = false
                        continue
                    }
                    when (s.status) {
                        SshSessionManager.SessionState.Status.CONNECTED -> {
                            if (!s.client.isConnected) {
                                Log.i(TAG, "MCP tunnel transport dropped — requesting reconnect")
                                sshSessionManager.updateStatus(
                                    sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                                )
                                sshSessionManager.requestReconnect(sid)
                                probeFails = 0
                            } else when (probeForward(s.client, mcpPort)) {
                                Probe.ALIVE -> probeFails = 0
                                Probe.SKIP -> { /* endpoint has no curl — can't probe */ }
                                Probe.DEAD -> {
                                    probeFails++
                                    Log.w(
                                        TAG,
                                        "MCP forward liveness probe failed " +
                                            "($probeFails/$PROBE_FAIL_THRESHOLD) on :$mcpPort",
                                    )
                                    if (probeFails >= PROBE_FAIL_THRESHOLD) {
                                        Log.w(TAG, "MCP forward wedged — forcing reconnect")
                                        probeFails = 0
                                        sshSessionManager.updateStatus(
                                            sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                                        )
                                        sshSessionManager.requestReconnect(sid)
                                    }
                                }
                            }
                        }
                        SshSessionManager.SessionState.Status.DISCONNECTED,
                        SshSessionManager.SessionState.Status.ERROR -> {
                            sshSessionManager.requestReconnect(sid)
                            probeFails = 0
                        }
                        else -> { /* CONNECTING / RECONNECTING — in progress */ }
                    }
                }
            }
        }
    }

    /**
     * Proactively revive the tunnel **now**, without waiting for the
     * [HEALTH_CHECK_MS] watchdog tick (a coroutine `delay` that Doze can defer
     * well past 15 s). Called by `SshConnectionService` on return-to-foreground
     * and on network-available — the two moments the standard SSH recovery paths
     * fire but which skip this headless session. Cheap + non-blocking: the
     * CONNECTED probe and any reconnect run on [scope].
     *
     *  - tunnel not started / no endpoint configured → no-op.
     *  - CONNECTED → one immediate end-to-end probe; force a reconnect only if
     *    the forward is actually wedged/dead (the watchdog would otherwise take
     *    up to [PROBE_FAIL_THRESHOLD] ticks to notice).
     *  - any other state (CONNECTING / RECONNECTING / DISCONNECTED / ERROR) →
     *    restart the establish job so the connect-retry backoff resets to
     *    [INITIAL_RETRY_MS] instead of waiting out an in-flight 30 s
     *    [SshSessionManager] reconnect backoff while the user is right there.
     */
    fun kickNow() {
        synchronized(lock) {
            val sid = sessionId ?: return
            val port = currentPort
            if (port <= 0) return
            val session = sshSessionManager.getSession(sid) ?: return
            if (session.status == SshSessionManager.SessionState.Status.CONNECTED) {
                val client = session.client
                scope.launch {
                    val dead = !client.isConnected || probeForward(client, port) == Probe.DEAD
                    if (dead) {
                        Log.i(TAG, "kickNow: MCP tunnel not healthy — forcing reconnect")
                        sshSessionManager.updateStatus(
                            sid, SshSessionManager.SessionState.Status.DISCONNECTED,
                        )
                        sshSessionManager.requestReconnect(sid)
                    }
                }
            } else {
                Log.i(TAG, "kickNow: MCP tunnel ${session.status} — restarting with fresh backoff")
                stopLocked()
                currentPort = port
                launchTunnel(port)
            }
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    /**
     * Multiplex any RUNNING guest-service ports that aren't already forwarded
     * onto the live tunnel session, without a reconnect. Called when a guest
     * service starts after the tunnel is already up. Additive only: a stopped
     * service's forward lingers (harmless — it just refuses connections) until
     * the next reconnect rebuilds the set from scratch in [establish].
     */
    fun refreshForwards() = synchronized(lock) {
        val sid = sessionId ?: return
        val s = sshSessionManager.getSession(sid) ?: return
        if (s.status != SshSessionManager.SessionState.Status.CONNECTED) return
        val already = s.activeForwards
            .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
            .map { it.bindPort }
            .toSet()
        val toAdd = guestServiceManager.runningPorts().distinct()
            .filter { it != currentPort && it !in already }
            .map { guestForwardInfo(it) }
        if (toAdd.isNotEmpty()) {
            sshSessionManager.applyPortForwards(sid, toAdd)
            Log.i(TAG, "Multiplexed guest ports onto MCP tunnel: ${toAdd.map { it.bindPort }}")
        }
    }

    /**
     * Expose the device's adbd (already listening on `127.0.0.1:[port]`) to the
     * MCP-tunnel endpoint as a **non-critical** REMOTE forward
     * (`127.0.0.1:[port]` on the endpoint ← phone `127.0.0.1:[port]`). The
     * phone-side hop is loopback, which the kernel never routes through a system
     * VpnService tun — so adb stays reachable even with a VPN active. Additive
     * and idempotent, mirroring [refreshForwards]; the caller persists the port
     * (UserPreferencesRepository.mcpAdbExposedPort) so [establish] re-arms it on
     * a full tunnel rebuild.
     *
     * Returns true if the forward is now bound on a live tunnel session; false
     * if the tunnel isn't currently up (the pref re-arm will bind it later) or
     * the bind failed.
     */
    fun exposeAdbPort(port: Int): Boolean = synchronized(lock) {
        val sid = sessionId ?: return false
        val s = sshSessionManager.getSession(sid) ?: return false
        if (s.status != SshSessionManager.SessionState.Status.CONNECTED) return false
        val alreadyBound = s.activeForwards.any {
            it.type == SshSessionManager.PortForwardType.REMOTE && it.bindPort == port
        }
        if (alreadyBound) return true
        val ok = sshSessionManager.applyPortForwards(sid, listOf(adbForwardInfo(port)))
        if (ok) {
            Log.i(TAG, "Exposed adb over MCP tunnel: -R $port")
        } else {
            Log.w(TAG, "adb -R $port failed to bind on MCP tunnel")
        }
        ok
    }

    /**
     * Tear down the adb reverse forward applied by [exposeAdbPort]: release the
     * endpoint bind and drop it from the session's activeForwards so a later
     * reconnect doesn't re-add it. No-op if the tunnel isn't up.
     */
    fun unexposeAdbPort(port: Int) {
        synchronized(lock) {
            val sid = sessionId ?: return
            sshSessionManager.getSession(sid) ?: return
            sshSessionManager.removePortForward(sid, adbForwardInfo(port))
            Log.i(TAG, "Removed adb forward from MCP tunnel: -R $port")
        }
    }

    private fun adbForwardInfo(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = ADB_FORWARD_RULE_ID,
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = true,
    )

    /**
     * A non-critical REMOTE forward for a guest MCP service port (e.g. a KiCad
     * MCP). Used both at [establish] time and by [refreshForwards] for a
     * live-add, so the two paths can't drift. selfHealOnBindFailure mirrors the
     * critical MCP forward and [adbForwardInfo]: a stale bind self-heals rather
     * than silently failing.
     */
    private fun guestForwardInfo(port: Int) = SshSessionManager.PortForwardInfo(
        ruleId = "guest-mcp-$port",
        type = SshSessionManager.PortForwardType.REMOTE,
        bindAddress = "127.0.0.1",
        bindPort = port,
        targetHost = "127.0.0.1",
        targetPort = port,
        critical = false,
        selfHealOnBindFailure = true,
    )

    private fun stopLocked() {
        job?.cancel()
        job = null
        teardownSession()
        currentPort = 0
    }

    /**
     * Tear down the headless tunnel session — release its server-side `-R`
     * binds and drop the [SshSessionManager] entry — WITHOUT cancelling the
     * supervising [job]. Lets the supervisory loop stand the `-R` down (e.g.
     * when WireGuard takes over the MCP transport) and re-establish later,
     * rather than ending the job. Reentrant under [lock] so [stopLocked]
     * reuses it. No-op if no session is up.
     */
    private fun teardownSession(reason: String? = null) = synchronized(lock) {
        val sid = sessionId ?: return@synchronized
        if (reason != null) Log.i(TAG, "Standing down MCP reverse tunnel: $reason")
        // Best-effort: release the server-side `-R` binds now, so a clean
        // re-enable / restart doesn't hit a stale bind. (A server only
        // reaps a dead client's forward on its own ClientAliveInterval, so
        // without this an immediate re-enable races the reaper. A hard
        // process kill skips this path and still relies on that reaper.)
        runCatching {
            val s = sshSessionManager.getSession(sid)
            val client = s?.client
            if (client != null && client.isConnected) {
                s.activeForwards
                    .filter { it.type == SshSessionManager.PortForwardType.REMOTE }
                    .forEach { fwd -> runCatching { client.delPortForwardingR(fwd.bindPort) } }
            }
        }
        sshSessionManager.removeSession(sid)
        sessionId = null
    }

    private enum class Outcome { ESTABLISHED, RETRY, FATAL }

    private suspend fun establish(mcpPort: Int): Outcome {
        val profileId = preferencesRepository.mcpTunnelEndpointProfileId.first()
        if (profileId.isNullOrBlank()) {
            Log.i(TAG, "No MCP tunnel endpoint configured — endpoint is on-device only")
            return Outcome.FATAL
        }
        val profile = connectionRepository.getById(profileId)
        if (profile == null) {
            Log.w(TAG, "MCP tunnel endpoint profile $profileId not found")
            return Outcome.FATAL
        }
        if (!profile.isSsh) {
            Log.w(TAG, "MCP tunnel endpoint profile ${profile.label} is not SSH — ignoring")
            return Outcome.FATAL
        }
        val auth = resolveHeadlessAuth(profile)
        if (auth == null) {
            // Expected, not a failure: a touch-required / encrypted-only endpoint
            // (e.g. a FIDO2 key) can't authenticate a *headless* connection. The
            // agent endpoint is carried by WireGuard (the default carrier); this
            // SSH -R is only a fallback, and it comes up on the user's interactive
            // session via that profile's saved MCP rule. So don't write a per-kick
            // FAILED connection-log entry (it read as a failure storm) — just note
            // it in logcat and stop the headless attempt.
            Log.i(
                TAG,
                "MCP -R fallback for ${profile.label} needs an interactive (e.g. key) " +
                    "connect; endpoint runs over WireGuard meanwhile",
            )
            return Outcome.FATAL
        }

        val config = ConnectionConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            authMethod = auth,
            sshOptions = ConnectionConfig.parseSshOptions(profile.sshOptions),
            addressFamily = ConnectionConfig.AddressFamily.valueOf(profile.addressFamilyEnum.name),
            // Unlimited self-healing: a headless tunnel that holds the MCP
            // forward should never give up while the endpoint is enabled.
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(
                autoReconnect = true,
                maxAttempts = 0,
                onNetworkChange = true,
            ),
        )

        val client = SshClient()
        val sid = sshSessionManager.registerSession(
            profileId = profile.id,
            label = "MCP tunnel: ${profile.label}",
            client = client,
            headless = true,
        )
        sessionId = sid
        return try {
            val hostKeyEntry = client.connect(config)
            // Silent TOFU — same policy the reconnect path uses: accept a
            // new host, abort on a changed key.
            when (val r = hostKeyVerifier.verify(hostKeyEntry)) {
                is HostKeyResult.Trusted -> { /* ok */ }
                is HostKeyResult.NewHost -> hostKeyVerifier.accept(r.entry)
                is HostKeyResult.KeyChanged -> {
                    Log.w(TAG, "Host key changed for ${profile.label} — refusing MCP tunnel")
                    cleanup(sid)
                    return Outcome.FATAL
                }
            }
            sshSessionManager.storeConnectionConfig(sid, config, SessionManager.NONE)
            sshSessionManager.updateStatus(sid, SshSessionManager.SessionState.Status.CONNECTED)

            // The critical forward: Haven's own MCP endpoint. A bind failure
            // here fails the whole (re)connect and retries.
            val forwards = mutableListOf(
                SshSessionManager.PortForwardInfo(
                    ruleId = "mcp-reverse-tunnel",
                    type = SshSessionManager.PortForwardType.REMOTE,
                    bindAddress = "127.0.0.1",
                    bindPort = mcpPort,
                    targetHost = "127.0.0.1",
                    targetPort = mcpPort,
                    critical = true,
                    selfHealOnBindFailure = true,
                ),
            )
            // Multiplex any running guest MCP servers (e.g. a KiCad MCP) onto
            // the same headless session as **non-critical** forwards: a guest
            // crash or stale guest bind must never tear down Haven's own
            // tunnel. They ride the same activeForwards re-apply on reconnect,
            // so they inherit the tunnel's roam/restart durability for free.
            guestServiceManager.runningPorts().distinct()
                .filter { it != mcpPort }
                .forEach { forwards.add(guestForwardInfo(it)) }
            // Re-arm a previously exposed adb port (expose_adb) as a
            // non-critical forward, so adb-over-the-tunnel survives a full
            // tunnel rebuild (app restart / re-enable), not just the in-memory
            // activeForwards re-apply that a network-blip reconnect does.
            preferencesRepository.mcpAdbExposedPort.first()?.let { adbPort ->
                if (adbPort != mcpPort && forwards.none { it.bindPort == adbPort }) {
                    forwards.add(adbForwardInfo(adbPort))
                }
            }
            if (!sshSessionManager.applyPortForwards(sid, forwards)) {
                Log.w(TAG, "MCP -R $mcpPort failed to bind on ${profile.label} (stale bind?) — retrying")
                cleanup(sid)
                return Outcome.RETRY
            }
            val guestPorts = forwards.drop(1).map { it.bindPort }
            Log.i(
                TAG,
                "MCP reverse tunnel up: -R $mcpPort" +
                    (if (guestPorts.isNotEmpty()) " (+guest ${guestPorts.joinToString(",")})" else "") +
                    " via ${profile.label}",
            )
            connectionLogRepository.logEvent(
                profile.id, ConnectionLog.Status.CONNECTED,
                details = "MCP reverse tunnel (-R $mcpPort)",
            )
            Outcome.ESTABLISHED
        } catch (e: Exception) {
            Log.w(TAG, "MCP tunnel connect to ${profile.label} failed: ${e.message}")
            cleanup(sid)
            Outcome.RETRY
        }
    }

    private fun cleanup(sid: String) {
        sshSessionManager.removeSession(sid)
        if (sessionId == sid) sessionId = null
    }

    /**
     * Resolve a non-interactive [ConnectionConfig.AuthMethod] for the
     * endpoint, or null if the profile would require a UI prompt
     * (encrypted/biometric key, FIDO2 hardware key, or no credential at
     * all). FIDO2 (`sk-`) and encrypted keys are excluded deliberately.
     */
    private suspend fun resolveHeadlessAuth(profile: ConnectionProfile): ConnectionConfig.AuthMethod? {
        val keyId = profile.keyId
        if (keyId != null) {
            val key = sshKeyRepository.getById(keyId) ?: return null
            if (key.keyType.startsWith("sk-") || key.isEncrypted) return null
            val keyBytes = sshKeyRepository.getDecryptedKeyBytes(keyId) ?: return null
            val certBytes = sshKeyRepository.getCertificateBytes(keyId)
            return ConnectionConfig.AuthMethod.PrivateKey(
                keyBytes = SshKeyExporter.toPem(keyBytes, key.keyType),
                certificateBytes = certBytes,
            )
        }
        // No explicit key — try every stored unencrypted, non-FIDO key.
        val usableKeys = sshKeyRepository.getAllDecrypted()
            .filter { !it.isEncrypted && !it.keyType.startsWith("sk-") }
        if (usableKeys.isNotEmpty()) {
            return ConnectionConfig.AuthMethod.PrivateKeys(
                keys = usableKeys.map {
                    ConnectionConfig.AuthMethod.PrivateKeys.KeyEntry(
                        label = it.label,
                        keyBytes = SshKeyExporter.toPem(it.privateKeyBytes, it.keyType),
                        certificateBytes = it.certificateBytes, // CA-only servers (#185)
                    )
                },
            )
        }
        val password = profile.sshPassword
        if (!password.isNullOrEmpty()) {
            return ConnectionConfig.AuthMethod.Password(password)
        }
        return null
    }

    /**
     * Probe the reverse forward end-to-end: ask the endpoint to HTTP-GET the
     * forwarded MCP port on its own loopback. A healthy `-R` round-trips back to
     * the device's MCP server and returns a status code fast (the server answers
     * 405/406 to a bare GET — any response proves the channel works); a wedged
     * forward hangs until the 4 s curl timeout, and a wedged *exec channel*
     * (transport itself stuck) is caught by [PROBE_TIMEOUT_MS] — both surface as
     * DEAD. SKIP when the endpoint lacks curl (we can't probe, so don't act).
     */
    private suspend fun probeForward(client: SshClient, port: Int): Probe {
        val cmd =
            "if command -v curl >/dev/null 2>&1; then " +
                "code=\$(curl -s -o /dev/null -m 4 -w '%{http_code}' " +
                "http://127.0.0.1:$port/mcp 2>/dev/null); rc=\$?; " +
                "echo \"P:\${code:-000}:\$rc\"; else echo P:NOCURL:127; fi"
        val stdout = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching { client.execCommand(cmd).stdout }.getOrNull()
        } ?: return Probe.DEAD
        return interpretProbeLine(stdout)
    }

    private companion object {
        const val ADB_FORWARD_RULE_ID = "adb-reverse"
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 30_000L
        const val HEALTH_CHECK_MS = 15_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val PROBE_FAIL_THRESHOLD = 2
    }
}

/** Result of a reverse-forward liveness probe (see [McpTunnelManager.probeForward]). */
internal enum class Probe { ALIVE, DEAD, SKIP }

/**
 * Interpret the endpoint probe's stdout (`P:<httpcode>:<rc>`, or `P:NOCURL:127`
 * when the endpoint has no curl). ALIVE iff the forward round-tripped to an HTTP
 * status (any 3-digit code other than 000); SKIP when we couldn't probe; DEAD
 * otherwise (curl timeout / connection refused → wedged or dead forward). Pure +
 * `internal` so the watchdog's decision logic is unit-testable.
 */
internal fun interpretProbeLine(stdout: String): Probe {
    val line = stdout.lineSequence().map { it.trim() }
        .firstOrNull { it.startsWith("P:") } ?: return Probe.SKIP
    val code = line.removePrefix("P:").substringBefore(":")
    if (code == "NOCURL") return Probe.SKIP
    return if (code.length == 3 && code.all { it.isDigit() } && code != "000") {
        Probe.ALIVE
    } else {
        Probe.DEAD
    }
}
