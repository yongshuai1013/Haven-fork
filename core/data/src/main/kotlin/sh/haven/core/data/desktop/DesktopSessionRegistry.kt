package sh.haven.core.data.desktop

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Live connection state of a remote-desktop (VNC/RDP) tab. */
enum class DesktopStatus { CONNECTING, CONNECTED, ERROR }

/** Current server cursor shape for a desktop tab. */
data class CursorSnapshot(val bitmap: Bitmap, val hotspotX: Int, val hotspotY: Int)

/**
 * A capturable handle to a live remote-desktop (VNC/RDP) tab's rendered output,
 * so the MCP layer can screenshot what the *viewer* shows — colours and all —
 * without coupling to `DesktopViewModel`. The tab registers lazy accessors; the
 * MCP `capture_desktop_tab` tool reads them on demand. Returns raw `Bitmap` +
 * primitives only (no UI types) to keep `core:data` UI-free.
 */
class DesktopFrameHandle(
    val protocol: String,
    val frame: () -> Bitmap?,
    val cursor: () -> CursorSnapshot?,
    val pointer: () -> Pair<Int, Int>,
)

/**
 * App-scoped mirror of the live state of remote-desktop tabs, keyed by the
 * connection profile id.
 *
 * Desktop tabs live in `DesktopViewModel`, but the connections list (a
 * separate ViewModel) needs to reflect whether a VNC/RDP profile's desktop is
 * connecting / connected so its row shows an accurate indicator. Rather than
 * coupling the two ViewModels, `DesktopViewModel` publishes per-profile status
 * here and `ConnectionsViewModel` observes it. This is the *real* desktop
 * status (the actual handshake), as opposed to merely "the SSH tunnel is up".
 */
@Singleton
class DesktopSessionRegistry @Inject constructor() {
    private val _statuses = MutableStateFlow<Map<String, DesktopStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DesktopStatus>> = _statuses.asStateFlow()

    /** Record [status] for [profileId] (no-op when [profileId] is null). */
    fun setStatus(profileId: String?, status: DesktopStatus) {
        if (profileId == null) return
        _statuses.update { it + (profileId to status) }
    }

    /** Drop any status for [profileId] — the desktop tab is gone. */
    fun clear(profileId: String?) {
        if (profileId == null) return
        _statuses.update { it - profileId }
    }

    // --- Capturable frame handles (for MCP capture_desktop_tab) ---

    private val frameHandles = ConcurrentHashMap<String, DesktopFrameHandle>()

    /** Register (or replace) the capturable handle for [profileId]. */
    fun registerFrameHandle(profileId: String?, handle: DesktopFrameHandle) {
        if (profileId == null) return
        frameHandles[profileId] = handle
    }

    /** Drop the frame handle for [profileId] — the desktop tab is gone. */
    fun clearFrameHandle(profileId: String?) {
        if (profileId == null) return
        frameHandles.remove(profileId)
    }

    /** The frame handle for [profileId], or null if none registered. */
    fun frameHandle(profileId: String): DesktopFrameHandle? = frameHandles[profileId]

    /** All registered frame handles, keyed by profileId. */
    fun frameHandles(): Map<String, DesktopFrameHandle> = frameHandles.toMap()
}
