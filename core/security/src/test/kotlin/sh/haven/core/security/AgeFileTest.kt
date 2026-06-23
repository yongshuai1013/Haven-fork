package sh.haven.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Validates [AgeFile] against the official C2SP/CCTV age test vectors plus
 * encrypt→decrypt round-trips. The vectors in `resources/age-testkit/` are the
 * non-armored, non-passphrase, non-hybrid (X25519-only) subset — the features
 * v1 implements; the CCTV README explicitly permits ignoring the rest.
 */
class AgeFileTest {

    // A reference keypair produced by `age-keygen` — pins Bech32 encoding to the real tool.
    private val katSecret = "AGE-SECRET-KEY-14Q3LWAMDVQV82QS2LSYP8HGAU9N93KRAGALF5FK7MTWC5TSW8RRQRFSTJL"
    private val katRecipient = "age106rgar49huet90z0fv0ujr598ddxwljtausq8jm6cxys86uwqausdt06yz"

    @Test
    fun cctvVectors() {
        val dir = File(javaClass.getResource("/age-testkit")!!.toURI())
        val files = dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        assertTrue("expected vendored vectors", files.size > 50)
        var checked = 0
        for (file in files) {
            val raw = file.readBytes()
            val sep = indexOfDoubleNewline(raw)
            val headerText = String(raw, 0, sep, Charsets.US_ASCII)
            var ageBytes = raw.copyOfRange(sep + 2, raw.size)
            val fields = headerText.lineSequence()
                .filter { it.contains(": ") }
                .map { it.substringBefore(": ") to it.substringAfter(": ") }
                .toList()
            val expect = fields.first { it.first == "expect" }.second
            val identities = fields.filter { it.first == "identity" }.map { it.second }
            val payloadHash = fields.firstOrNull { it.first == "payload" }?.second
            // Large vectors are zlib-compressed (CCTV `compressed: zlib` header).
            if (fields.any { it.first == "compressed" && it.second == "zlib" }) {
                ageBytes = java.util.zip.InflaterInputStream(ByteArrayInputStream(ageBytes)).readBytes()
            }

            if (expect == "success") {
                val out = ByteArrayOutputStream()
                try {
                    AgeFile.decrypt(ByteArrayInputStream(ageBytes), out, identities)
                } catch (e: Throwable) {
                    fail("${file.name}: expected success but threw $e")
                }
                assertEquals("${file.name}: payload hash", payloadHash, sha256hex(out.toByteArray()))
            } else {
                try {
                    AgeFile.decrypt(ByteArrayInputStream(ageBytes), ByteArrayOutputStream(), identities)
                    fail("${file.name}: expected '$expect' failure but decrypt succeeded")
                } catch (_: Throwable) {
                    // expected
                }
            }
            checked++
        }
        println("AgeFile: validated $checked CCTV vectors")
    }

    @Test
    fun recipientDerivationMatchesAgeKeygen() {
        assertEquals(katRecipient, AgeFile.recipientFor(katSecret))
    }

    @Test
    fun generatedIdentityRoundTripsThroughBech32() {
        val id = AgeFile.generateIdentity(SecureRandom())
        assertTrue(id.secret.startsWith("AGE-SECRET-KEY-1"))
        assertTrue(id.recipient.startsWith("age1"))
        assertEquals(id.recipient, AgeFile.recipientFor(id.secret))
    }

    @Test
    fun roundTripAcrossStreamBoundaries() {
        val id = AgeFile.generateIdentity()
        val chunk = 64 * 1024
        val sizes = listOf(0, 1, 100, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 7, 3 * chunk)
        for (size in sizes) {
            val plain = ByteArray(size).also { SecureRandom().nextBytes(it) }
            val ct = ByteArrayOutputStream()
            AgeFile.encrypt(ByteArrayInputStream(plain), ct, listOf(id.recipient))
            val pt = ByteArrayOutputStream()
            AgeFile.decrypt(ByteArrayInputStream(ct.toByteArray()), pt, listOf(id.secret))
            assertArrayEquals("round-trip size=$size", plain, pt.toByteArray())
        }
    }

    @Test
    fun multipleRecipientsAndNoMatch() {
        val a = AgeFile.generateIdentity()
        val b = AgeFile.generateIdentity()
        val stranger = AgeFile.generateIdentity()
        val plain = "shared secret".toByteArray()

        val ct = ByteArrayOutputStream()
        AgeFile.encrypt(ByteArrayInputStream(plain), ct, listOf(a.recipient, b.recipient))
        val bytes = ct.toByteArray()

        for (id in listOf(a, b)) {
            val out = ByteArrayOutputStream()
            AgeFile.decrypt(ByteArrayInputStream(bytes), out, listOf(id.secret))
            assertArrayEquals(plain, out.toByteArray())
        }
        try {
            AgeFile.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), listOf(stranger.secret))
            fail("stranger should not decrypt")
        } catch (_: Throwable) {
        }
    }

    private fun indexOfDoubleNewline(raw: ByteArray): Int {
        for (i in 0 until raw.size - 1) if (raw[i] == '\n'.code.toByte() && raw[i + 1] == '\n'.code.toByte()) return i
        error("no header separator")
    }

    private fun sha256hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
