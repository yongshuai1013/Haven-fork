package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A user-defined inbound-email automation rule: when a message arriving in [folderId] of
 * [accountProfileId] (null = any connected email account) matches [criteriaJson], run the
 * ordered actions in [actionsJson]. Criteria/actions are JSON strings (codebase convention —
 * no Room TypeConverters); see `sh.haven.core.data.mailrule.MailRuleJson`.
 *
 * Creating + enabling a rule is the standing authorization for its actions (the engine fires
 * without a foreground consent prompt); the master switch, per-rule [enabled], and the
 * firing log are the witnesses that keep this non-silent.
 */
@Entity(tableName = "mail_rules")
data class MailRule(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    /** Evaluation order; lower runs first. */
    val orderIndex: Int = 0,
    /** Watched account; null = ANY connected email profile. */
    val accountProfileId: String? = null,
    val folderId: String = "INBOX",
    val criteriaJson: String,
    val actionsJson: String,
    /** When true, a match stops evaluation of later rules for the same message. */
    val stopOnMatch: Boolean = false,
    /** When true, raise a notification each time this rule fires. */
    val notifyOnFire: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long? = null,
)
