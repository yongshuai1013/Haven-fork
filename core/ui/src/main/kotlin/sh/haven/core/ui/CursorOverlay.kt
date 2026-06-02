package sh.haven.core.ui

import android.graphics.Bitmap

/**
 * Remote cursor shape, to be drawn at the local pointer position.
 *
 * Shared by the VNC and RDP desktop viewers (#212): VNC receives it via the
 * Cursor pseudo-encoding, RDP via IronRDP server-pointer updates. Lives in
 * `core:ui` so `feature:rdp` can use it without depending on `feature:vnc`.
 */
data class CursorOverlay(
    val bitmap: Bitmap,
    val hotspotX: Int,
    val hotspotY: Int,
)
