package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Regression tests for [SshCertificateParser]. Focus: FIDO/SK certificate
 * field alignment (#208 finding 12). An `sk-ssh-ed25519` cert carries an extra
 * `application` string after the public key; if the parser skips it, every
 * field after the key (serial, type, keyId, principals, validity) is read from
 * the wrong offset.
 */
class SshCertificateParserTest {

    @Test
    fun `parses sk-ssh-ed25519 cert fields past the application string`() {
        val blob = buildSkEd25519Cert(
            application = "ssh:haven",
            serial = 7L,
            keyId = "sk-alice@phone",
            principals = listOf("alice", "alice@example.com"),
            validAfter = 1_700_000_000L,
            validBefore = 1_700_086_400L,
        )
        val text = "sk-ssh-ed25519-cert-v01@openssh.com " +
            Base64.getEncoder().encodeToString(blob)

        val cert = SshCertificateParser.parse(text.toByteArray(Charsets.US_ASCII))

        assertNotNull("SK cert should parse", cert)
        cert!!
        assertEquals(7L, cert.serial)
        assertEquals("sk-alice@phone", cert.keyId)
        assertEquals(listOf("alice", "alice@example.com"), cert.validPrincipals)
        assertEquals(1_700_000_000L, cert.validAfter)
        assertEquals(1_700_086_400L, cert.validBefore)
    }

    @Test
    fun `plain ssh-ed25519 cert still parses correctly`() {
        // Guard against the exact-match rewrite breaking the non-SK path.
        val blob = buildEd25519Cert(serial = 3L, keyId = "bob", principals = listOf("bob"))
        val text = "ssh-ed25519-cert-v01@openssh.com " +
            Base64.getEncoder().encodeToString(blob)
        val cert = SshCertificateParser.parse(text.toByteArray(Charsets.US_ASCII))
        assertNotNull(cert)
        assertEquals("bob", cert!!.keyId)
        assertEquals(3L, cert.serial)
        assertEquals(listOf("bob"), cert.validPrincipals)
    }

    // ---- toOpenSshPublicKeyLine: tolerate every stored cert shape (#133/#185) ----

    @Test
    fun `toOpenSshPublicKeyLine accepts the raw binary blob`() {
        val blob = buildEd25519Cert(serial = 1L, keyId = "x", principals = listOf("x"))
        val line = SshCertificateParser.toOpenSshPublicKeyLine(blob).decodeToString()
        assertEquals(
            "ssh-ed25519-cert-v01@openssh.com " + Base64.getEncoder().encodeToString(blob),
            line,
        )
    }

    @Test
    fun `toOpenSshPublicKeyLine accepts bare base64 text (step-ca crt shape)`() {
        // The pre-fix bug: "Generate via step-ca" stored step-ca's `crt`
        // field — bare standard-base64 of the blob, no type prefix — verbatim.
        // toOpenSshPublicKeyLine must decode it, not choke with "invalid
        // certificate type length".
        val blob = buildEd25519Cert(serial = 1L, keyId = "x", principals = listOf("x"))
        val base64TextBytes = Base64.getEncoder().encodeToString(blob).toByteArray(Charsets.US_ASCII)
        val line = SshCertificateParser.toOpenSshPublicKeyLine(base64TextBytes).decodeToString()
        assertEquals(
            "ssh-ed25519-cert-v01@openssh.com " + Base64.getEncoder().encodeToString(blob),
            line,
        )
    }

    @Test
    fun `toOpenSshPublicKeyLine accepts a full openssh text line and drops the comment`() {
        val blob = buildEd25519Cert(serial = 1L, keyId = "x", principals = listOf("x"))
        val b64 = Base64.getEncoder().encodeToString(blob)
        val fileLine = "ssh-ed25519-cert-v01@openssh.com $b64 alice@laptop"
            .toByteArray(Charsets.US_ASCII)
        val line = SshCertificateParser.toOpenSshPublicKeyLine(fileLine).decodeToString()
        assertEquals("ssh-ed25519-cert-v01@openssh.com $b64", line)
    }

    @Test
    fun `all three stored shapes normalise to the same binary blob`() {
        val blob = buildEd25519Cert(serial = 9L, keyId = "y", principals = listOf("y"))
        val b64 = Base64.getEncoder().encodeToString(blob)
        val fromBinary = SshCertificateParser.normalizeToBinaryBlob(blob)
        val fromBase64 = SshCertificateParser.normalizeToBinaryBlob(b64.toByteArray(Charsets.US_ASCII))
        val fromLine = SshCertificateParser.normalizeToBinaryBlob(
            "ssh-ed25519-cert-v01@openssh.com $b64 c".toByteArray(Charsets.US_ASCII),
        )
        assertEquals(true, blob.contentEquals(fromBinary))
        assertEquals(true, blob.contentEquals(fromBase64))
        assertEquals(true, blob.contentEquals(fromLine))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toOpenSshPublicKeyLine still rejects non-certificate garbage`() {
        // Neither valid binary nor base64-of-a-cert: must surface the clear
        // diagnostic, not silently succeed.
        SshCertificateParser.toOpenSshPublicKeyLine(ByteArray(12) { 0xFF.toByte() })
    }

    // ---- fixture builders ----

    private val nonce = ByteArray(32) { (it + 1).toByte() }
    private val pubKey32 = ByteArray(32) { 0xAB.toByte() }
    private val caKeyBlob = ByteArrayOutputStream().also { o ->
        writeString(o, "ssh-ed25519")
        writeBytes(o, ByteArray(32) { 0xCD.toByte() })
    }.toByteArray()
    private val sigBlob = ByteArrayOutputStream().also { o ->
        writeString(o, "ssh-ed25519")
        writeBytes(o, ByteArray(64) { 0xEF.toByte() })
    }.toByteArray()

    private fun buildSkEd25519Cert(
        application: String,
        serial: Long,
        keyId: String,
        principals: List<String>,
        validAfter: Long,
        validBefore: Long,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        writeString(out, "sk-ssh-ed25519-cert-v01@openssh.com")
        writeBytes(out, nonce)
        writeBytes(out, pubKey32)          // ed25519 public key
        writeString(out, application)      // SK extra field
        writeUint64(out, serial)
        writeUint32(out, OpenSshCertificate.USER_CERT_TYPE)
        writeString(out, keyId)
        writeBytes(out, packPrincipals(principals))
        writeUint64(out, validAfter)
        writeUint64(out, validBefore)
        writeBytes(out, byteArrayOf()) // critical options
        writeBytes(out, byteArrayOf()) // extensions
        writeBytes(out, byteArrayOf()) // reserved
        writeBytes(out, caKeyBlob)
        writeBytes(out, sigBlob)
    }.toByteArray()

    private fun buildEd25519Cert(
        serial: Long,
        keyId: String,
        principals: List<String>,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        writeString(out, "ssh-ed25519-cert-v01@openssh.com")
        writeBytes(out, nonce)
        writeBytes(out, pubKey32)
        writeUint64(out, serial)
        writeUint32(out, OpenSshCertificate.USER_CERT_TYPE)
        writeString(out, keyId)
        writeBytes(out, packPrincipals(principals))
        writeUint64(out, 0L)
        writeUint64(out, Long.MAX_VALUE)
        writeBytes(out, byteArrayOf())
        writeBytes(out, byteArrayOf())
        writeBytes(out, byteArrayOf())
        writeBytes(out, caKeyBlob)
        writeBytes(out, sigBlob)
    }.toByteArray()

    private fun packPrincipals(principals: List<String>): ByteArray =
        ByteArrayOutputStream().also { p -> principals.forEach { writeString(p, it) } }.toByteArray()

    private fun writeUint32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeUint64(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeBytes(out: ByteArrayOutputStream, b: ByteArray) {
        writeUint32(out, b.size)
        out.write(b)
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        writeBytes(out, s.toByteArray(Charsets.US_ASCII))
    }
}
