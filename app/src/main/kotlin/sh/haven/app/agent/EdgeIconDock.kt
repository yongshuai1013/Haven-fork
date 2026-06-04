package sh.haven.app.agent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.PresentedMedia
import sh.haven.core.data.agent.PresentedMediaKind
import kotlin.math.roundToInt

/**
 * The in-app dock of backgrounded app windows. Mounted at the top of the tree
 * (next to [PresentationHost]) so it floats over whatever screen is active —
 * but, like the other hosts, it renders nothing and intercepts no touches when
 * there's nothing to show.
 *
 * Each backgrounded ([AgentPresentationManager.minimizedIds]) app window gets a
 * small rounded icon showing its live VNC frame (so you can see what each one
 * is doing); tap restores it to the full overlay, the ✕ tears its cage down.
 * The whole dock is draggable and snaps to the nearest left/right edge so it
 * never sits over the middle of the content.
 *
 * Shares the Activity-scoped [PresentationHostViewModel] with [PresentationHost]
 * (both resolve via `hiltViewModel()` against the same Activity store), so the
 * focus/minimize/restore state is one source of truth.
 */
@Composable
internal fun EdgeIconDock(viewModel: PresentationHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val minimized by viewModel.minimizedIds.collectAsStateWithLifecycle()

    // App windows + minimized images / web / audio all dock as edge icons.
    val docked = pending.filter { it.id in minimized }
    if (docked.isEmpty()) return

    // A presentation sheet (the focused window, or an image/audio) renders in a
    // ModalBottomSheet with a full-screen scrim *above* this in-tree dock, so
    // icons would be visible-but-untappable behind it. Hide the dock while any
    // sheet is open; to reach a parked window the user backgrounds the focused
    // one first (tap-outside or Background), which closes the sheet and reveals
    // the dock.
    val sheetOpen = pending.any { it.id !in minimized }
    if (sheetOpen) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentW = constraints.maxWidth.toFloat()
        val parentH = constraints.maxHeight.toFloat()
        val iconPx = with(density) { ICON_SIZE.toPx() }
        val marginPx = with(density) { 8.dp.toPx() }

        // Drag state in px; snaps to the nearest horizontal edge on release.
        // Initial park: right edge, ~30% down.
        var offsetX by remember { mutableStateOf(parentW - iconPx - marginPx) }
        var offsetY by remember { mutableStateOf(parentH * 0.3f) }

        Column(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                        },
                        onDragEnd = {
                            // Snap to whichever side is nearer.
                            val centerX = offsetX + iconPx / 2f
                            offsetX = if (centerX < parentW / 2f) {
                                marginPx
                            } else {
                                parentW - iconPx - marginPx
                            }
                            offsetY = offsetY.coerceIn(0f, (parentH - iconPx).coerceAtLeast(0f))
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            docked.forEach { media ->
                EdgeIcon(
                    media = media,
                    controller = viewModel.controllerFor(media),
                    onRestore = { viewModel.restore(media.id) },
                    onClose = { viewModel.dismiss(media) },
                )
            }
        }
    }
}

private val ICON_SIZE = 64.dp

@Composable
private fun EdgeIcon(
    media: PresentedMedia,
    controller: AppWindowVncController?,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val frame = controller?.frame?.collectAsStateWithLifecycle()?.value
    // A minimized image shows a thumbnail of its cached file; an app window
    // shows its live VNC frame; web/audio fall back to a caption letter.
    val imageThumb = if (media.kind == PresentedMediaKind.IMAGE && media.filePath != null) {
        produceState<android.graphics.Bitmap?>(initialValue = null, media.filePath) {
            value = withContext(Dispatchers.Default) {
                runCatching { android.graphics.BitmapFactory.decodeFile(media.filePath) }.getOrNull()
            }
        }.value
    } else {
        null
    }
    Surface(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .size(ICON_SIZE)
            .pointerInput(media.id) {
                detectTapGestures(onTap = { onRestore() })
            },
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val thumb = frame?.asImageBitmap() ?: imageThumb?.asImageBitmap()
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = media.caption ?: "Minimized item",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // No frame/thumbnail (web/audio, or still decoding): caption letter.
                Text(
                    text = (media.caption ?: "•").take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Close affordance — tears the cage down (vs tap = restore).
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(media.id) {
                        detectTapGestures(onTap = { onClose() })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close ${media.caption ?: "app window"}",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
