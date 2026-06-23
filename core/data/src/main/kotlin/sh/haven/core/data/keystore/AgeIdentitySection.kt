package sh.haven.core.data.keystore

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import sh.haven.core.data.db.AgeIdentityDao
import sh.haven.core.data.db.entities.AgeIdentityEntity
import sh.haven.core.security.CredentialEncryption
import sh.haven.core.security.KeyKind
import sh.haven.core.security.KeystoreEntry
import sh.haven.core.security.KeystoreFetch
import sh.haven.core.security.KeystoreFlag
import sh.haven.core.security.KeystoreSection
import sh.haven.core.security.KeystoreStore
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AgeIdentitySection"

/**
 * [KeystoreSection] over the `age_identities` table. Surfaces each stored
 * age identity in the security audit screen with its public `age1…`
 * recipient as [KeystoreEntry.publicMaterial]; [enumerate] never exposes
 * the private key, and [fetch] decrypts the `AGE-SECRET-KEY-1…` on demand.
 */
@Singleton
class AgeIdentitySection @Inject constructor(
    private val ageIdentityDao: AgeIdentityDao,
    @ApplicationContext private val appContext: Context,
) : KeystoreSection {

    override val store: KeystoreStore = KeystoreStore.AGE_IDENTITIES

    override suspend fun enumerate(): List<KeystoreEntry> =
        ageIdentityDao.getAll().map { toEntry(it) }

    override suspend fun wipe(entryId: String): Boolean {
        val existed = ageIdentityDao.getById(entryId) != null
        if (existed) ageIdentityDao.deleteById(entryId)
        return existed
    }

    override suspend fun fetch(entryId: String): KeystoreFetch {
        val row = ageIdentityDao.getById(entryId) ?: return KeystoreFetch.NotFound
        return try {
            val plain = if (CredentialEncryption.isEncrypted(row.secret)) {
                CredentialEncryption.decrypt(appContext, row.secret)
            } else {
                row.secret
            }
            KeystoreFetch.Bytes(plain.toByteArray(Charsets.US_ASCII))
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $entryId: ${e.message}")
            KeystoreFetch.Failed("Decryption failed")
        }
    }

    private fun toEntry(row: AgeIdentityEntity): KeystoreEntry {
        val flags = mutableSetOf<KeystoreFlag>()
        if (CredentialEncryption.isEncrypted(row.secret)) flags.add(KeystoreFlag.HARDWARE_BACKED)
        return KeystoreEntry(
            id = row.id,
            store = KeystoreStore.AGE_IDENTITIES,
            keyKind = KeyKind.AGE_IDENTITY,
            label = row.label,
            algorithm = "age-x25519",
            publicMaterial = row.recipient,
            fingerprint = null,
            createdAt = row.createdAt,
            flags = flags,
        )
    }
}
