package sh.haven.core.data.keystore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.security.CredentialEncryption

/**
 * Locks in [CredentialEncryption.isEncrypted] after security-review #18:
 * it must require a well-formed Tink AEAD body (≥33 bytes, TINK output-prefix
 * version 0x01), not merely the "ENC:" prefix, so a plaintext credential that
 * happens to start with "ENC:" is treated as plaintext. Pure JVM — uses
 * java.util.Base64 so it runs without Robolectric.
 */
class CredentialEncryptionIsEncryptedTest {

    private fun encShaped(size: Int, first: Byte = 1): String =
        "ENC:" + java.util.Base64.getEncoder()
            .encodeToString(ByteArray(size).also { if (size > 0) it[0] = first })

    @Test
    fun `well-formed tink ciphertext is recognised`() {
        assertTrue(CredentialEncryption.isEncrypted(encShaped(40)))
    }

    @Test
    fun `plaintext is not encrypted`() {
        assertFalse(CredentialEncryption.isEncrypted("hunter2"))
        assertFalse(CredentialEncryption.isEncrypted(""))
    }

    @Test
    fun `ENC-prefixed plaintext is not mistaken for ciphertext`() {
        // Invalid base64 body (the hyphen), and a valid-but-too-short body.
        assertFalse(CredentialEncryption.isEncrypted("ENC:secret-cipher-text"))
        assertFalse(CredentialEncryption.isEncrypted("ENC:aGVsbG8=")) // "hello" = 5 bytes < 33
    }

    @Test
    fun `wrong tink prefix byte is not encrypted`() {
        assertFalse(CredentialEncryption.isEncrypted(encShaped(40, first = 2)))
    }

    @Test
    fun `too-short body is not encrypted`() {
        assertFalse(CredentialEncryption.isEncrypted(encShaped(10)))
    }
}
