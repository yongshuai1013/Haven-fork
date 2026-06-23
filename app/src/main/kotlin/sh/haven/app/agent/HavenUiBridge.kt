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
            val view = injectableView() ?: return@withContext injectionRefusal()
            val downTime = SystemClock.uptimeMillis()
            dispatch(view, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
            if (holdMs > 0) delay(holdMs)
            dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
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
        val view = injectableView() ?: return@withContext injectionRefusal()
        val n = steps.coerceIn(1, 200)
        val stepDelay = (durationMs / n).coerceAtLeast(1)
        val downTime = SystemClock.uptimeMillis()
        dispatch(view, downTime, downTime, MotionEvent.ACTION_DOWN, fromX, fromY)
        for (i in 1..n) {
            delay(stepDelay)
            val t = i.toFloat() / n
            val ix = (fromX + (toX - fromX) * t).toInt()
            val iy = (fromY + (toY - fromY) * t).toInt()
            dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, ix, iy)
        }
        dispatch(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, toX, toY)
        InjectResult.Delivered
    }

    /**
     * The decor view to inject into, or null when injection must be refused
     * (a consent prompt is showing — the self-confirm guard — or no activity
     * is foreground). On a null return, [injectionRefusal] gives the reason.
     * Both must run on the main thread, back to back, within one dispatch.
     */
    private fun injectableView(): View? {
        if (consentManager.pending.value.isNotEmpty()) return null
        return activityRef?.get()?.window?.decorView
    }

    /** The refusal that pairs with a null [injectableView] (re-checks the same state). */
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
     * Phase 1: the **activity window only**. Compose dialogs / bottom
     * sheets render in separate windows that [captureScreen] and
     * [dispatchTap] don't currently reach either; multi-window coverage is
     * a follow-up. Reads the public [SemanticsNode] API; the two owner /
     * root-node accessors are `internal`, so they're reached by reflection
     * (defensively, scanning for the getter by name).
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
        val composeView = findComposeView(decor)
            ?: return@withContext DumpResult.Failed("No Compose view in the foreground window.")
        val owner = reflectGetter(composeView, "getSemanticsOwner") as? SemanticsOwner
            ?: return@withContext DumpResult.Failed("Compose semantics owner not reachable.")
        val root = (reflectGetter(owner, "getUnmergedRootSemanticsNode") as? SemanticsNode)
            ?: (reflectGetter(owner, "getRootSemanticsNode") as? SemanticsNode)
            ?: return@withContext DumpResult.Failed("Compose root semantics node not reachable.")
        val nodes = ArrayList<UiNode>()
        try {
            walkSemantics(root, nodes)
        } catch (e: Throwable) {
            return@withContext DumpResult.Failed("Semantics walk failed: ${e.message}")
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

    private fun walkSemantics(node: SemanticsNode, out: MutableList<UiNode>) {
        val cfg = node.config
        val text = cfg.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotEmpty() }
        val desc = cfg.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotEmpty() }
        val editable = cfg.getOrNull(SemanticsProperties.EditableText)?.text
        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString()
        val clickable = cfg.contains(SemanticsActions.OnClick)
        val disabled = cfg.contains(SemanticsProperties.Disabled)
        if (text != null || desc != null || editable != null || clickable) {
            val b = node.boundsInWindow
            out += UiNode(
                text = text,
                contentDescription = desc,
                editableText = editable,
                role = role,
                clickable = clickable,
                disabled = disabled,
                left = b.left.toInt(),
                top = b.top.toInt(),
                right = b.right.toInt(),
                bottom = b.bottom.toInt(),
            )
        }
        for (child in node.children) walkSemantics(child, out)
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
    )

    /** Outcome of [dumpUi]. */
    sealed interface DumpResult {
        data class Ok(val nodes: List<UiNode>, val width: Int, val height: Int) : DumpResult
        object Secure : DumpResult
        data class NoForeground(val reason: String) : DumpResult
        data class Failed(val reason: String) : DumpResult
    }
}
