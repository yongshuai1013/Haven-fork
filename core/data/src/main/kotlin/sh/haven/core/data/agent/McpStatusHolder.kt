package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The WireGuard MCP endpoint is shadowed by another app's system VPN that holds
 * the same [address] (on interface [vpnInterface]). Structured so the UI can
 * build a localized warning.
 */
data class WgCollisionInfo(
    val vpnInterface: String,
    val address: String,
)

/**
 * Live MCP tool-call activity, surfaced in the foreground-service notification
 * so a backgrounded agent session is *visible* — running, what it's doing now,
 * and the last error — instead of dropping silently (#239).
 *
 * [inFlight] counts concurrently-executing `tools/call` dispatches (the server
 * handles each connection on its own thread); [lastTool] is the most recently
 * dispatched tool; [lastError] is the last failure as `"tool: message"`.
 */
data class McpActivity(
    val running: Boolean = false,
    val inFlight: Int = 0,
    val lastTool: String? = null,
    val callCount: Int = 0,
    val lastError: String? = null,
)

/**
 * Whether the "near" (SSH) MCP carrier is currently riding a live interactive
 * session — see `app/.../agent/McpNearCarrier.kt`. [active] false with
 * [profileId] set means the configured endpoint profile just isn't connected
 * right now (open it to enable MCP-over-near); both null means no endpoint
 * profile is configured at all.
 */
data class NearCarrierStatus(
    val active: Boolean = false,
    val profileId: String? = null,
    val profileLabel: String? = null,
)

/**
 * Cross-module bridge for live MCP-server status that UI feature modules need to
 * observe. The MCP server itself lives in `:app`, which feature modules can't
 * depend on, so it publishes runtime status here (in `core/data`) and feature
 * ViewModels (e.g. Settings) read it.
 *
 * Carries the **WireGuard MCP carrier collision** (non-null when an active
 * system VPN holds the same address Haven's userspace WireGuard netstack
 * serves MCP on, which silently shadows that endpoint — see
 * `McpServer.detectVpnAddressCollision`) and the **near (SSH) carrier**'s live
 * state, so a Settings screen (or an agent via `get_app_info`) can show which
 * MCP routes are actually open right now instead of guessing.
 */
@Singleton
class McpStatusHolder @Inject constructor() {
    private val _wireguardCollision = MutableStateFlow<WgCollisionInfo?>(null)
    val wireguardCollision: StateFlow<WgCollisionInfo?> = _wireguardCollision.asStateFlow()

    fun setWireguardCollision(info: WgCollisionInfo?) {
        _wireguardCollision.value = info
    }

    private val _nearCarrier = MutableStateFlow(NearCarrierStatus())
    val nearCarrier: StateFlow<NearCarrierStatus> = _nearCarrier.asStateFlow()

    fun setNearCarrier(status: NearCarrierStatus) {
        _nearCarrier.value = status
    }

    private val _activity = MutableStateFlow(McpActivity())

    /** Live tool-call activity for the foreground notification (#239). */
    val activity: StateFlow<McpActivity> = _activity.asStateFlow()

    /** Server accept-loop up/down. */
    fun setRunning(running: Boolean) = _activity.update { it.copy(running = running) }

    /** A `tools/call` dispatch began. */
    fun callStarted(tool: String) = _activity.update {
        it.copy(inFlight = it.inFlight + 1, lastTool = tool, callCount = it.callCount + 1)
    }

    /** A `tools/call` dispatch finished; [error] non-null records the failure. */
    fun callFinished(tool: String, error: String? = null) = _activity.update {
        it.copy(
            inFlight = (it.inFlight - 1).coerceAtLeast(0),
            lastError = error?.let { e -> "$tool: ${e.take(140)}" } ?: it.lastError,
        )
    }
}
