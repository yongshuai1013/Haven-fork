package sh.haven.app.tasker

import android.content.Intent
import android.os.Bundle

/**
 * Locale/Tasker/MacroDroid plugin contract for the "Run command on a Haven
 * server" action (#367).
 *
 * Implements the twofortyfouram Locale Developer Platform *setting* contract
 * directly (just intent actions + a Bundle) rather than pulling in the
 * `com.twofortyfouram` library — the surface we use is tiny and stable:
 *
 * - The host (Tasker/MacroDroid) launches [ACTION_EDIT_SETTING] on our
 *   [TaskerEditActivity]; we return `RESULT_OK` with [EXTRA_BUNDLE] (our
 *   config) and [EXTRA_BLURB] (a short human summary shown on the action).
 * - When the macro fires, the host broadcasts [ACTION_FIRE_SETTING] to
 *   [TaskerFireReceiver] with the same [EXTRA_BUNDLE].
 *
 * The config [Bundle] carries [BUNDLE_PROFILE_ID] / [BUNDLE_PROFILE_LABEL]
 * (the selected Haven connection), [BUNDLE_COMMAND] (a shell command — the
 * host substitutes its own variables into this string before firing),
 * [BUNDLE_OVERLAY] (show the command live in Haven's terminal), and
 * [BUNDLE_BLOCK] (hold the macro until the command finishes).
 */
object TaskerPlugin {
    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"

    /** The config Bundle, both directions. */
    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"

    /** Short human summary of the action (≤ ~60 chars), edit → host only. */
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    // Tasker/Locale variable passback (net.dinglisch.android.tasker.TaskerPlugin).
    // In "wait until finished" mode the fire receiver returns the command's
    // output to the host as local variables the macro can use downstream.
    /** Result-extras key holding the returned-variables Bundle. */
    const val EXTRA_VARIABLES_BUNDLE = "net.dinglisch.android.tasker.extras.VARIABLES"
    /** EDIT-result extra (String[]) declaring which variables this action sets. */
    const val BUNDLE_KEY_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES"
    const val VAR_STDOUT = "%hstdout"
    const val VAR_STDERR = "%hstderr"
    const val VAR_EXIT = "%hexit"
    val RELEVANT_VARIABLES = arrayOf(VAR_STDOUT, VAR_STDERR, VAR_EXIT)

    const val BUNDLE_PROFILE_ID = "sh.haven.tasker.PROFILE_ID"
    const val BUNDLE_PROFILE_LABEL = "sh.haven.tasker.PROFILE_LABEL"
    const val BUNDLE_COMMAND = "sh.haven.tasker.COMMAND"
    const val BUNDLE_OVERLAY = "sh.haven.tasker.OVERLAY"
    const val BUNDLE_BLOCK = "sh.haven.tasker.BLOCK"

    /** Build the config Bundle the edit activity hands back to the host. */
    fun buildBundle(
        profileId: String,
        profileLabel: String,
        command: String,
        overlay: Boolean,
        block: Boolean,
    ): Bundle = Bundle().apply {
        putString(BUNDLE_PROFILE_ID, profileId)
        putString(BUNDLE_PROFILE_LABEL, profileLabel)
        putString(BUNDLE_COMMAND, command)
        putBoolean(BUNDLE_OVERLAY, overlay)
        putBoolean(BUNDLE_BLOCK, block)
    }

    /**
     * A config Bundle is valid only if it carries a non-blank profile id and
     * command — guards against a host replaying a Bundle from an older/other
     * plugin version (the contract's "reject unknown Bundle" rule).
     */
    fun isValid(bundle: Bundle?): Boolean =
        bundle != null &&
            !bundle.getString(BUNDLE_PROFILE_ID).isNullOrBlank() &&
            !bundle.getString(BUNDLE_COMMAND).isNullOrBlank()

    /**
     * Resolve the config for a fired [intent]. Prefers the Locale nested
     * [EXTRA_BUNDLE] (how Tasker/MacroDroid's plugin flow passes it); falls
     * back to the same keys as **flat** intent extras, so the action also
     * works from a generic "Send Intent" step (and is reachable via `adb
     * shell am broadcast`, which can't build a nested Bundle). Returns null
     * if neither form carries a valid profile id + command.
     */
    fun configFrom(intent: Intent): Bundle? {
        intent.getBundleExtra(EXTRA_BUNDLE)?.let { if (isValid(it)) return it }
        val flat = Bundle().apply {
            putString(BUNDLE_PROFILE_ID, intent.getStringExtra(BUNDLE_PROFILE_ID))
            putString(BUNDLE_PROFILE_LABEL, intent.getStringExtra(BUNDLE_PROFILE_LABEL))
            putString(BUNDLE_COMMAND, intent.getStringExtra(BUNDLE_COMMAND))
            putBoolean(BUNDLE_OVERLAY, intent.getBooleanExtra(BUNDLE_OVERLAY, false))
            putBoolean(BUNDLE_BLOCK, intent.getBooleanExtra(BUNDLE_BLOCK, false))
        }
        return if (isValid(flat)) flat else null
    }
}
