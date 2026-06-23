package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * An age X25519 file-encryption identity. [recipient] is the public
 * `age1…` string (not secret — used to encrypt to this key); [secret] is
 * the `AGE-SECRET-KEY-1…` private key, stored encrypted at rest
 * (`ENC:`+Base64 via `CredentialEncryption`, the same envelope as profile
 * passwords and TOTP secrets). The encrypt/decrypt boundary is
 * `AgeIdentityRepository`.
 */
@Entity(tableName = "age_identities")
data class AgeIdentityEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    /** Public `age1…` recipient. */
    val recipient: String,
    /** `AGE-SECRET-KEY-1…` private key. Encrypted at rest; plaintext only in memory. */
    val secret: String,
    val createdAt: Long = System.currentTimeMillis(),
)
