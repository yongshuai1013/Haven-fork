package sh.haven.core.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import sh.haven.core.fido.SkKeyParser
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses SSH private key files using JSch.
 * Supports PEM (PKCS#8, PKCS#1, EC), OpenSSH, PuTTY PPK, and Dropbear formats.
 * The original file bytes are stored as-is — JSch parses them again at connect time.
 * Dropbear keys are converted to OpenSSH format on import.
 */
object SshKeyImporter {

    data class ImportedKey(
        val keyType: String,
        val privateKeyBytes: ByteArray,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
        val isEncrypted: Boolean = false,
    )

    class EncryptedKeyException : Exception("Key is encrypted — passphrase required")

    /** Thrown when the key file is a FIDO2 SK key that needs special handling. */
    class SkKeyDetectedException(val fileBytes: ByteArray) :
        Exception("FIDO2 security key file detected")

    /**
     * Parse a private key file and extract metadata for storage.
     *
     * @throws EncryptedKeyException if the key is encrypted and no passphrase given
     * @throws SkKeyDetectedException if the file is a FIDO2 SK key
     * @throws IllegalArgumentException if the passphrase is wrong or key is unreadable
     */
    fun import(fileBytes: ByteArray, passphrase: String? = null): ImportedKey {
        // Detect FIDO2 SK keys before JSch (which doesn't support them)
        if (SkKeyParser.isSkKeyFile(fileBytes)) {
            throw SkKeyDetectedException(fileBytes)
        }

        // Convert Dropbear keys to OpenSSH format (JSch doesn't support Dropbear)
        val keyBytes = if (DropbearKeyConverter.isDropbearKey(fileBytes)) {
            DropbearKeyConverter.toOpenSsh(fileBytes)
        } else {
            fileBytes
        }

        val jsch = JSch()
        val kpair = try {
            KeyPair.load(jsch, keyBytes, null)
        } catch (e: Exception) {
            throw IllegalArgumentException("Unrecognised key format: ${e.message}", e)
        }

        val encrypted = kpair.isEncrypted

        try {
            // Temporarily decrypt to extract public key metadata, but store
            // the original encrypted bytes. The passphrase will be prompted
            // at connection time — private keys never stored in plaintext.
            if (encrypted) {
                if (passphrase.isNullOrEmpty()) {
                    throw EncryptedKeyException()
                }
                if (!kpair.decrypt(passphrase)) {
                    throw IllegalArgumentException("Incorrect passphrase")
                }
            }

            val pubBlob = kpair.publicKeyBlob
                ?: throw IllegalArgumentException("Could not extract public key")

            val keyTypeName = readKeyTypeName(pubBlob)
            val pubB64 = Base64.getEncoder().encodeToString(pubBlob)
            val publicKeyOpenSsh = "$keyTypeName $pubB64"

            val digest = MessageDigest.getInstance("SHA-256").digest(pubBlob)
            val fpB64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
            val fingerprint = "SHA256:$fpB64"

            // Store original key bytes as-is. For encrypted keys, the passphrase
            // is prompted at connection time and passed to JSch's addIdentity().
            // For Dropbear keys, store the converted OpenSSH bytes (JSch can't parse Dropbear).
            val storedBytes = keyBytes

            return ImportedKey(
                keyType = keyTypeName,
                privateKeyBytes = storedBytes,
                publicKeyOpenSsh = publicKeyOpenSsh,
                fingerprintSha256 = fingerprint,
                isEncrypted = encrypted,
            )
        } finally {
            kpair.dispose()
        }
    }

    /** Read the key type name from the first field of an SSH wire format public key blob. */
    private fun readKeyTypeName(pubBlob: ByteArray): String {
        if (pubBlob.size < 4) throw IllegalArgumentException("Public key blob too short")
        val len = ((pubBlob[0].toInt() and 0xFF) shl 24) or
                ((pubBlob[1].toInt() and 0xFF) shl 16) or
                ((pubBlob[2].toInt() and 0xFF) shl 8) or
                (pubBlob[3].toInt() and 0xFF)
        if (len <= 0 || 4 + len > pubBlob.size) throw IllegalArgumentException("Invalid public key blob")
        return String(pubBlob, 4, len, Charsets.US_ASCII)
    }
}
