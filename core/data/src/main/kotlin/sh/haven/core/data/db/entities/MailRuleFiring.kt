package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The durable record of one rule firing (or a system event) — the user-visible audit
 * trail AND the crash-safety belt. One row per `(rule, message-UID)`; the
 * [actionsCompletedMask] bit-tracks which of the rule's ordered actions have finished, so
 * a crash mid-batch can resume without repeating a non-idempotent action (delete/forward).
 *
 * [kind] distinguishes a real firing from system events ([KIND_UIDVALIDITY_RESET],
 * [KIND_POLL_SKIPPED]) that carry a null [ruleId].
 */
@Entity(
    tableName = "mail_rule_firings",
    indices = [Index(value = ["profileId", "folderId", "uid"]), Index(value = ["ruleId"])],
)
data class MailRuleFiring(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Null for system rows (UIDVALIDITY reset, skipped poll). */
    val ruleId: String?,
    val profileId: String,
    val folderId: String,
    val uid: Long,
    val messageSubject: String? = null,
    val firedAt: Long = System.currentTimeMillis(),
    val kind: String = KIND_FIRED,
    /** Bitmask of action indices already completed (crash-replay idempotency). */
    val actionsCompletedMask: Long = 0,
    /** One-line per-action outcome summary for the UI. */
    val outcomeSummary: String? = null,
) {
    companion object {
        const val KIND_FIRED = "FIRED"
        const val KIND_UIDVALIDITY_RESET = "UIDVALIDITY_RESET"
        const val KIND_POLL_SKIPPED = "POLL_SKIPPED_DISCONNECTED"
    }
}
