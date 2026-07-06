package sh.haven.core.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Encrypts/decrypts credential strings (passwords) using Tink AEAD backed by Android Keystore.
 *
 * Shares the same master key as [KeyEncryption] but uses distinct associated data
 * so password ciphertext cannot be confused with SSH key ciphertext.
 *
 * Encrypted values are stored as "ENC:" + Base64(ciphertext) so they can live in
 * TEXT columns alongside legacy plaintext values. Plaintext values are migrated
 * transparently on first read/write.
 */
object CredentialEncryption {

    private const val KEYSET_NAME = "haven_credential_keyset"
    private const val PREFERENCE_FILE = "haven_credential_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://haven_credential_master"

    private val ASSOCIATED_DATA = "haven-credential".toByteArray()
    private const val ENCRYPTED_PREFIX = "ENC:"

    // Tink AEAD (AES256_GCM, TINK output prefix) ciphertext starts with a
    // 1-byte version 0x01 + 4-byte key id, followed by the 12-byte GCM nonce,
    // ciphertext, and 16-byte tag — so a genuine value is at least 33 bytes and
    // begins with 0x01. Used to reject a plaintext that merely happens to start
    // with "ENC:" (security-review #18).
    private const val TINK_PREFIX_VERSION: Byte = 0x01
    private const val TINK_MIN_CIPHERTEXT_LEN = 5 + 12 + 16

    @Volatile
    private var aead: Aead? = null

    private fun getAead(context: Context): Aead {
        aead?.let { return it }
        synchronized(this) {
            aead?.let { return it }
            AeadConfig.register()
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREFERENCE_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            return keysetHandle.getPrimitive(Aead::class.java).also { aead = it }
        }
    }

    /** Encrypt a password string. Returns "ENC:" + Base64(ciphertext). */
    fun encrypt(context: Context, plaintext: String): String {
        val ciphertext = getAead(context).encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return ENCRYPTED_PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** Decrypt a password string. Handles both encrypted ("ENC:...") and legacy plaintext. */
    fun decrypt(context: Context, stored: String): String {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored // legacy plaintext
        val ciphertext = Base64.decode(stored.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
        return String(getAead(context).decrypt(ciphertext, ASSOCIATED_DATA), Charsets.UTF_8)
    }

    /**
     * True if the value is Haven-encrypted ciphertext. Requires the "ENC:"
     * prefix AND a well-formed Tink AEAD body, so a plaintext credential that
     * merely starts with "ENC:" is (correctly) treated as plaintext and gets
     * encrypted on the next save rather than being left in the clear (#18).
     */
    fun isEncrypted(stored: String): Boolean {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return false
        return try {
            // java.util.Base64 (not android.util.Base64) so this stays a pure
            // predicate callable from plain JVM unit tests; the standard-alphabet
            // decoder round-trips the NO_WRAP output that encrypt() writes.
            val body = java.util.Base64.getDecoder().decode(stored.removePrefix(ENCRYPTED_PREFIX))
            body.size >= TINK_MIN_CIPHERTEXT_LEN && body[0] == TINK_PREFIX_VERSION
        } catch (_: Exception) {
            false
        }
    }
}
