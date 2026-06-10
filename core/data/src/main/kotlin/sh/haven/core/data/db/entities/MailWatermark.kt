package sh.haven.core.data.db.entities

import androidx.room.Entity

/**
 * The per-`(profileId, folderId)` high-water-mark the poller uses to find new mail.
 *
 * [lastSeenUid] is the highest IMAP UID already evaluated; the next poll fetches UIDs
 * strictly greater. [uidValidity] guards against a server renumbering the mailbox: if it
 * changes, every stored UID is meaningless — the engine resets [lastSeenUid] to the
 * current max and does NOT replay (replaying would re-run destructive actions over the
 * whole mailbox). [uidNext] is the server's advertised next-UID, kept for diagnostics.
 */
@Entity(tableName = "mail_watermarks", primaryKeys = ["profileId", "folderId"])
data class MailWatermark(
    val profileId: String,
    val folderId: String,
    val uidValidity: Long,
    val lastSeenUid: Long,
    val uidNext: Long? = null,
    val lastPolledAt: Long = System.currentTimeMillis(),
)
