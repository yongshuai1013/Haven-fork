package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A single destructive action (delete / move-to-trash / forward / run-command, or an
 * MCP tool whose ConsentLevel is non-NEVER) that a rule matched while Haven was
 * backgrounded. Per the notify-only default, it is NOT executed in the background —
 * it is queued here and surfaced for the user to approve on next foreground.
 *
 * [actionJson] is the single serialised [sh.haven.core.data.mailrule.MailRuleAction];
 * [messageId] is the encoded `"folder uid"` so the executor can re-fetch on approval.
 */
@Entity(tableName = "mail_rule_pending_actions")
data class MailRulePendingAction(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val profileId: String,
    val folderId: String,
    val uid: Long,
    val messageId: String,
    val messageSubject: String? = null,
    val actionJson: String,
    val queuedAt: Long = System.currentTimeMillis(),
)
