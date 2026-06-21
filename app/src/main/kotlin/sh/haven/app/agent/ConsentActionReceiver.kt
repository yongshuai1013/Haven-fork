package sh.haven.app.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.haven.core.data.agent.AgentConsentManager
import javax.inject.Inject

/**
 * Handles the **Allow / Deny** action buttons on the backgrounded-consent
 * notification (raised by [sh.haven.app.HavenApp] from
 * [AgentConsentManager.blockedWhileBackground]).
 *
 * The original tool call already failed closed (instant DENY) — the consent
 * gate never blocks. Tapping **Allow** here arms a short retry window for the
 * (client, tool) via [AgentConsentManager.armRetryWindow], so the agent's
 * retry of that action proceeds. **Deny** just dismisses the notification (the
 * call was already denied). This is how the user gets a real choice without
 * the consent gate ever waiting — the queue-and-wait approach was reverted
 * because it broke the fail-closed contract.
 *
 * The Allow action is marked `setAuthenticationRequired` (API 31+) by the
 * notification builder, so approving requires the device to be unlocked.
 */
@AndroidEntryPoint
class ConsentActionReceiver : BroadcastReceiver() {

    @Inject lateinit var consentManager: AgentConsentManager

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        if (notifId != -1) NotificationManagerCompat.from(context).cancel(notifId)

        if (intent.action != ACTION_ALLOW) {
            Log.i(TAG, "consent notification: Deny/dismiss (call already failed closed)")
            return
        }
        val tool = intent.getStringExtra(EXTRA_TOOL)
        if (tool.isNullOrEmpty()) return
        val client = intent.getStringExtra(EXTRA_CLIENT)

        // armRetryWindow is suspend; keep the receiver alive across it.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                consentManager.armRetryWindow(client, tool)
            } catch (e: Exception) {
                Log.w(TAG, "armRetryWindow failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ConsentAction"
        const val ACTION_ALLOW = "sh.haven.app.agent.CONSENT_ALLOW"
        const val ACTION_DENY = "sh.haven.app.agent.CONSENT_DENY"
        const val EXTRA_CLIENT = "consent_client"
        const val EXTRA_TOOL = "consent_tool"
        const val EXTRA_NOTIF_ID = "consent_notif_id"
    }
}
