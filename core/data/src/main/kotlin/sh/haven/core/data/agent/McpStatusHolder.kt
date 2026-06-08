package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Cross-module bridge for live MCP-server status that UI feature modules need to
 * observe. The MCP server itself lives in `:app`, which feature modules can't
 * depend on, so it publishes runtime status here (in `core/data`) and feature
 * ViewModels (e.g. Settings) read it.
 *
 * Currently carries the **WireGuard MCP carrier collision**: non-null when an
 * active system VPN holds the same address Haven's userspace WireGuard netstack
 * serves MCP on, which silently shadows that endpoint. See
 * `McpServer.detectVpnAddressCollision`.
 */
@Singleton
class McpStatusHolder @Inject constructor() {
    private val _wireguardCollision = MutableStateFlow<WgCollisionInfo?>(null)
    val wireguardCollision: StateFlow<WgCollisionInfo?> = _wireguardCollision.asStateFlow()

    fun setWireguardCollision(info: WgCollisionInfo?) {
        _wireguardCollision.value = info
    }
}
