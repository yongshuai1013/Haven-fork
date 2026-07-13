package sh.haven.feature.connections

/**
 * Pure state helpers for the "Tunnel through SSH" editor rows shared by
 * VNC / RDP / SPICE / SMB in [ConnectionEditDialog]. Each protocol keeps
 * its own flag/carrier-ID pair on ConnectionProfile; these helpers (plus
 * the SshTunnelBlock composable) centralise the behaviour so the four
 * rows can't drift.
 */

/**
 * Strict initial toggle state used by VNC and SPICE: enabled only when the
 * stored profile is actually of this [type], the stored [flag] is set, AND
 * a carrier profile is selected. Guards against stale rows — the flag
 * historically defaulted true, and a type-switched profile can still carry
 * another protocol's flag. RDP and SMB intentionally initialise from the
 * raw stored flag instead (a missing carrier there surfaces as the
 * "Select SSH connection" dropdown plus a blocked Save).
 */
internal fun strictTunnelInitialEnabled(
    storedType: String?,
    type: String,
    flag: Boolean,
    carrierId: String?,
): Boolean = storedType == type && flag && carrierId != null

/**
 * Host rewrite when the tunnel toggle flips. Enabling defaults a blank or
 * `localhost` host to `127.0.0.1` — the IPv4 loopback, so the remote sshd
 * doesn't resolve the IPv6 loopback first and fail against a server bound
 * to IPv4 only (VNC tunnel fix, v5.24.14). Disabling clears only the
 * loopback values the toggle itself put there; a custom host survives
 * round trips of the switch.
 */
internal fun tunnelHostOnToggle(enabled: Boolean, host: String): String = when {
    enabled && (host.isBlank() || host == "localhost") -> "127.0.0.1"
    !enabled && (host == "127.0.0.1" || host == "localhost") -> ""
    else -> host
}

/** Save is blocked while the tunnel is enabled but no carrier is picked. */
internal fun tunnelComplete(enabled: Boolean, carrierId: String?): Boolean =
    !enabled || carrierId != null

/** Persist the carrier ID only while the tunnel is enabled. */
internal fun tunnelCarrierForSave(enabled: Boolean, carrierId: String?): String? =
    if (enabled) carrierId else null
