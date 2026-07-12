package sh.haven.app.agent

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.AgentConsentManager
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Bridge that lets the MCP agent endpoint **see and drive Haven's own
 * UI** — the perception/drive half of the self-hosting loop (VISION.md
 * §1a, "The self-hosting loop"). `install_apk_from_backend` already lets
 * an agent act on Haven's own body; this lets it perceive the result
 * (capture the rendered screen) and drive the app (inject pointer input)
 * in the same pixel space, so a release-verify "drive the new build and
 * diff the screen" cycle can run.
 *
 * It holds a [WeakReference] to the foreground [MainActivity], registered
 * via [attach]/[detach] from the Activity's onResume/onPause (the same
 * lifecycle hook `FidoAuthenticator.setActiveActivity` uses). Capture and
 * injection are no-ops when no activity is foreground — there is nothing
 * to capture and nothing safe to tap.
 *
 * ### Why the app module, not core/data
 *
 * Capture needs the Activity's [android.view.Window] for [PixelCopy] and
 * injection needs to dispatch [MotionEvent]s to its decor view. Those are
 * UI-host concerns; keeping the Activity reference here keeps the
 * substrate layer (core/data) free of Activity/Window/MotionEvent types.
 *
 * ### Trust posture
 *
 * - **Capture** is gated at the tool layer as a "let the agent see
 *   Haven's screen" action (ONCE_PER_SESSION), matching `capture_desktop_tab`.
 * - **Injection** is gated EVERY_CALL, matching the terminal coordinate
 *   verbs (`tap_terminal`/`drag_terminal`).
 * - **FLAG_SECURE**: when the user enables Settings → screen security,
 *   [captureScreen] returns [CaptureResult.Secure] rather than the blank
 *   bitmap PixelCopy would hand back for a secured window — an honest
 *   signal, never a misleading black frame.
 * - **Self-confirm guard**: injection is refused while *any* consent
 *   prompt is pending ([AgentConsentManager.pending] non-empty), so an
 *   injected tap can never reach into the consent sheet and approve a
 *   different call. The consent channel stays a human-only gate.
 */
@Singleton
class HavenUiBridge @Inject constructor(
    private val consentManager: AgentConsentManager,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    /**
     * Compose sheets/dialogs render in their own windows, which [dumpUi]'s walk
     * of the activity's decorView never reached — so the consent sheet, the
     * app's most safety-critical UI, was invisible to an agent (#355). Rather
     * than reflect into `WindowManagerGlobal` (a non-SDK interface), each
     * overlay opts in by registering its own Compose view via
     * [OverlayUiRegistration]. Weak refs: a dismissed sheet's view must not be
     * retained here.
     */
    private val overlays = mutableListOf<Pair<String, WeakReference<View>>>()

    /** Register an overlay window's Compose view for [dumpUi]. See [OverlayUiRegistration]. */
    fun registerOverlay(label: String, view: View) = synchronized(overlays) {
        overlays.removeAll { it.second.get() == null || it.second.get() === view }
        overlays.add(label to WeakReference(view))
    }

    fun unregisterOverlay(view: View) = synchronized(overlays) {
        overlays.removeAll { it.second.get() == null || it.second.get() === view }
        Unit
    }

    private fun overlaySnapshot(): List<Pair<String, View>> = synchronized(overlays) {
        overlays.mapNotNull { (label, ref) -> ref.get()?.let { label to it } }
    }

    /** One of the app's on-screen windows: its root [decor] view, screen-origin, and a dump label. */
    private class WindowInfo(val decor: View, val locX: Int, val locY: Int, val label: String?)

    /**
     * The app's attached, visible windows in z-order (bottom→top) — the
     * activity window PLUS any Compose dialogs / dropdown menus / bottom
     * sheets, which each render in their own window (multi-window coverage,
     * the follow-up the earlier activity-only phase deferred). Reflects into
     * `WindowManagerGlobal.mViews`; that is per-PROCESS, so it only ever
     * exposes Haven's own windows (no cross-app leak). Falls back to the
     * activity window alone if the greylist field is unavailable, so behaviour
     * degrades to the previous phase rather than breaking.
     */
    private fun appWindows(activity: Activity): List<WindowInfo> {
        val activityDecor = activity.window.decorView
        val loc = IntArray(2)
        fun infoFor(v: View, label: String?): WindowInfo {
            v.getLocationOnScreen(loc)
            return WindowInfo(v, loc[0], loc[1], label)
        }
        val roots = windowManagerRootViews()
            ?: return listOf(infoFor(activityDecor, null))
        val overlaysNow = overlaySnapshot()
        var extra = 0
        return roots
            .filter { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            .map { v ->
                val label = when {
                    v === activityDecor -> null
                    else -> overlaysNow.firstOrNull { isDescendant(v, it.second) }?.first
                        ?: "window-${++extra}"
                }
                infoFor(v, label)
            }
    }

    /** `WindowManagerGlobal.getInstance().mViews`, or null if the greylist path is unavailable. */
    private fun windowManagerRootViews(): List<View>? = try {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val instance = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        when (val value = field.get(instance)) {
            is List<*> -> value.filterIsInstance<View>()
            is Array<*> -> value.filterIsInstance<View>()
            else -> null
        }.takeIf { it != null && it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    private fun isDescendant(root: View, target: View): Boolean {
        if (root === target) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) if (isDescendant(root.getChildAt(i), target)) return true
        }
        return false
    }

    /** Called from [MainActivity.onResume]: this activity is now foreground. */
    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    /**
     * Called from [MainActivity.onPause]. Only clears if [activity] is the
     * one we hold, so a fast background→foreground hand-off (a new activity
     * attaches in onResume before the old one's onPause fires) doesn't wipe
     * the live reference. Mirrors `FidoAuthenticator.clearActiveActivity`.
     */
    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    /**
     * Capture Haven's own currently-rendered window into a [Bitmap], on the
     * main thread, via [PixelCopy] (which reads the hardware-composited
     * surface — the only correct path for Compose's hardware layers; a
     * Canvas-draw of the decor view yields blank for accelerated content).
     *
     * Captures the ACTIVITY window's pixels. A Compose dialog / dropdown menu
     * over it renders in a separate window whose Surface is not reachable to
     * composite here, so it may not appear in the image — but [dumpUi] does
     * report those windows' elements (in this same coordinate space), so an
     * agent drives popups off the dump, not the pixels.
     */
    suspend fun captureScreen(): CaptureResult = withContext(Dispatchers.Main.immediate) {
        val activity = activityRef?.get()
            ?: return@withContext CaptureResult.NoForeground(
                "Haven is not in the foreground — bring the app forward, then retry.",
            )
        val window = activity.window
        if ((window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return@withContext CaptureResult.Secure
        }
        val view = window.decorView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            return@withContext CaptureResult.NoForeground(
                "Haven's window has not laid out yet — retry shortly.",
            )
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val code = suspendCancellableCoroutine<Int> { cont ->
            PixelCopy.request(window, bitmap, { result -> cont.resume(result) }, mainHandler)
        }
        if (code == PixelCopy.SUCCESS) {
            CaptureResult.Ok(bitmap, w, h)
        } else {
            bitmap.recycle()
            CaptureResult.Failed("PixelCopy failed (code $code)")
        }
    }

    /**
     * Inject a tap (or, with [holdMs] > 0, a press-and-hold) at window-pixel
     * ([x], [y]) — the same coordinate space [captureScreen] reports. A
     * long-press is realised by genuinely suspending [holdMs] between
     * ACTION_DOWN and ACTION_UP, because Compose's gesture detector measures
     * elapsed wall-clock, not event timestamps.
     */
    suspend fun dispatchTap(x: Int, y: Int, holdMs: Long): InjectResult =
        withContext(Dispatchers.Main.immediate) {
            val (view, lx, ly) = injectTarget(x, y) ?: return@withContext injectionRefusal()
            val downTime = SystemClock.uptimeMillis()
            dispatch(view, downTime, downTime, MotionEvent.ACTION_DOWN, lx, ly)
            if (holdMs > 0) delay(holdMs)
            dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, lx, ly)
            InjectResult.Delivered
        }

    /**
     * Inject a swipe/drag from ([fromX],[fromY]) to ([toX],[toY]) over
     * [durationMs], split into [steps] ACTION_MOVE events. Drives the real
     * dispatch pipeline (pager flings, list scrolls, etc.).
     */
    suspend fun dispatchSwipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
        steps: Int,
    ): InjectResult = withContext(Dispatchers.Main.immediate) {
        // A drag stays within one window: pick it by the START point and apply
        // the same activity-space→window-local offset to every interpolated step.
        val target = injectTarget(fromX, fromY) ?: return@withContext injectionRefusal()
        val view = target.first
        val offX = target.second - fromX
        val offY = target.third - fromY
        val n = steps.coerceIn(1, 200)
        val stepDelay = (durationMs / n).coerceAtLeast(1)
        val downTime = SystemClock.uptimeMillis()
        dispatch(view, downTime, downTime, MotionEvent.ACTION_DOWN, fromX + offX, fromY + offY)
        for (i in 1..n) {
            delay(stepDelay)
            val t = i.toFloat() / n
            val ix = (fromX + (toX - fromX) * t).toInt() + offX
            val iy = (fromY + (toY - fromY) * t).toInt() + offY
            dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, ix, iy)
        }
        dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX + offX, toY + offY)
        InjectResult.Delivered
    }

    /**
     * The window to inject a tap at ([x],[y]) into — the topmost app window
     * whose bounds contain that point (so a dropdown menu / dialog above the
     * activity receives the tap, not the covered activity beneath it) — with
     * the point translated into that window's local coordinates. Null when
     * injection must be refused (a consent prompt is showing — the
     * self-confirm guard — or no activity is foreground).
     *
     * [x]/[y] are in the activity-window coordinate space the dump and capture
     * report; the activity window's own origin is subtracted back out so a
     * child window's local coordinates come out right.
     */
    private fun injectTarget(x: Int, y: Int): Triple<View, Int, Int>? {
        if (consentManager.pending.value.isNotEmpty()) return null
        val activity = activityRef?.get() ?: return null
        val windows = appWindows(activity)
        val activityDecor = activity.window.decorView
        val loc = IntArray(2).also { activityDecor.getLocationOnScreen(it) }
        // Caller coords are activity-window space; map to absolute screen.
        val screenX = x + loc[0]
        val screenY = y + loc[1]
        // Topmost (last in z-order) window containing the point.
        val hit = windows.lastOrNull { w ->
            screenX >= w.locX && screenX < w.locX + w.decor.width &&
                screenY >= w.locY && screenY < w.locY + w.decor.height
        } ?: return Triple(activityDecor, x, y)
        return Triple(hit.decor, screenX - hit.locX, screenY - hit.locY)
    }

    /** The refusal that pairs with a null [injectTarget] (re-checks the same state). */
    private fun injectionRefusal(): InjectResult.Refused =
        if (consentManager.pending.value.isNotEmpty()) {
            InjectResult.Refused(
                "a consent prompt is showing — input injection is blocked so it can't self-confirm",
            )
        } else {
            InjectResult.Refused("Haven is not in the foreground")
        }

    private fun dispatch(view: View, downTime: Long, eventTime: Long, action: Int, x: Int, y: Int) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        try {
            view.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    /** Outcome of [captureScreen]. */
    sealed interface CaptureResult {
        /** Success: [bitmap] is [width]×[height] window pixels (caller owns/recycles it). */
        data class Ok(val bitmap: Bitmap, val width: Int, val height: Int) : CaptureResult

        /** Window is FLAG_SECURE — capture is intentionally unavailable. */
        object Secure : CaptureResult

        /** No foreground activity / window not laid out — [reason] is user-facing. */
        data class NoForeground(val reason: String) : CaptureResult

        /** PixelCopy returned a non-success code; [reason] carries it. */
        data class Failed(val reason: String) : CaptureResult
    }

    /** Outcome of [dispatchTap]/[dispatchSwipe]. */
    sealed interface InjectResult {
        object Delivered : InjectResult
        data class Refused(val reason: String) : InjectResult
    }

    /**
     * Dump the foreground window's Compose semantics tree — the in-app
     * equivalent of `uiautomator dump`, so an agent gets exact element
     * bounds (no visual estimation off a downscaled capture) and can feed
     * them straight to [dispatchTap]. Bounds are window-relative pixels,
     * the same space [captureScreen]/[dispatchTap] use.
     *
     * Multi-window: the activity window PLUS any Compose dialogs / dropdown
     * menus / bottom sheets, each of which renders in its own window (via
     * [appWindows]). A child window's node bounds are translated into the
     * activity-window coordinate space that [dispatchTap] and [captureScreen]
     * use, so a bound read from the dump can be tapped directly. Reads the
     * public [SemanticsNode] API; the two owner / root-node accessors are
     * `internal`, so they're reached by reflection (defensively, scanning for
     * the getter by name).
     */
    suspend fun dumpUi(): DumpResult = withContext(Dispatchers.Main.immediate) {
        val activity = activityRef?.get()
            ?: return@withContext DumpResult.NoForeground(
                "Haven is not in the foreground — bring the app forward, then retry.",
            )
        val window = activity.window
        if ((window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
            return@withContext DumpResult.Secure
        }
        val decor = window.decorView
        val activityLoc = IntArray(2).also { decor.getLocationOnScreen(it) }
        val nodes = ArrayList<UiNode>()
        var walkedAny = false
        // Each window is best-effort: a failure to walk one (a popup mid-
        // animation, an unexpected view root) must not lose the others' nodes.
        for (win in appWindows(activity)) {
            runCatching {
                val cv = findComposeView(win.decor) ?: return@runCatching
                val owner = reflectGetter(cv, "getSemanticsOwner") as? SemanticsOwner ?: return@runCatching
                val root = (reflectGetter(owner, "getUnmergedRootSemanticsNode") as? SemanticsNode)
                    ?: (reflectGetter(owner, "getRootSemanticsNode") as? SemanticsNode)
                    ?: return@runCatching
                // Offset a child window's node bounds into activity-window space.
                walkSemantics(
                    root, nodes, window = win.label,
                    offsetX = win.locX - activityLoc[0], offsetY = win.locY - activityLoc[1],
                )
                walkedAny = true
            }
        }
        if (!walkedAny) {
            return@withContext DumpResult.Failed("No Compose semantics reachable in any foreground window.")
        }
        DumpResult.Ok(nodes, decor.width, decor.height)
    }

    private fun findComposeView(v: View): View? {
        if (v.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findComposeView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Invoke a zero-arg getter by exact name, then by name-prefix (Kotlin `internal` getters may be mangled). */
    private fun reflectGetter(target: Any, name: String): Any? = try {
        val m = target.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == name }
            ?: target.javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name.startsWith(name) }
        m?.invoke(target)
    } catch (e: Throwable) {
        null
    }

    private fun walkSemantics(
        node: SemanticsNode,
        out: MutableList<UiNode>,
        window: String?,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ) {
        val cfg = node.config
        val text = cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotEmpty() }
        val desc = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        val editable = cfg.getOrNull(SemanticsProperties.EditableText)?.text
        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString()
        val clickable = cfg.contains(SemanticsActions.OnClick)
        val disabled = cfg.contains(SemanticsProperties.Disabled)
        if (text != null || desc != null || editable != null || clickable) {
            // boundsInWindow is relative to this node's OWN window; offset by the
            // window's origin so every node reports in one (activity-window) space.
            val b = node.boundsInWindow
            out += UiNode(
                text = text,
                contentDescription = desc,
                editableText = editable,
                role = role,
                clickable = clickable,
                disabled = disabled,
                left = b.left.toInt() + offsetX,
                top = b.top.toInt() + offsetY,
                right = b.right.toInt() + offsetX,
                bottom = b.bottom.toInt() + offsetY,
                window = window,
            )
        }
        for (child in node.children) walkSemantics(child, out, window, offsetX, offsetY)
    }

    /** One semantic element: its text/desc/role, whether it's clickable/editable, and window-pixel bounds. */
    data class UiNode(
        val text: String?,
        val contentDescription: String?,
        val editableText: String?,
        val role: String?,
        val clickable: Boolean,
        val disabled: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        /**
         * Which window the node lives in: null for the activity window,
         * otherwise the overlay's label ("consent-sheet", "biometric-gate", …).
         * Compose sheets and dialogs are separate windows; before #355 they
         * were absent from the dump entirely.
         */
        val window: String? = null,
    )

    /** Outcome of [dumpUi]. */
    sealed interface DumpResult {
        data class Ok(val nodes: List<UiNode>, val width: Int, val height: Int) : DumpResult
        object Secure : DumpResult
        data class NoForeground(val reason: String) : DumpResult
        data class Failed(val reason: String) : DumpResult
    }
}
