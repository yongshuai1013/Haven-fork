package sh.haven.core.security

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The age file-encryption format (v1), implemented on BouncyCastle primitives
 * so it works on API 26+ (Android's JCA lacks X25519 / ChaCha20-Poly1305 that
 * far back). Interoperable with the upstream `age` CLI.
 *
 * v1 scope: **X25519 recipients only** — passphrase (scrypt) recipients and
 * ASCII armor are not implemented (decryption of such files fails rather than
 * silently mis-handling them). The format is validated against the C2SP/CCTV
 * age test vectors plus round-trips with the reference `age` binary; see
 * `AgeFileTest`.
 *
 * Spec: https://age-encryption.org/v1
 */
object AgeFile {
    private const val VERSION_LINE = "age-encryption.org/v1"
    private const val FILE_KEY_SIZE = 16
    private const val CHUNK_SIZE = 64 * 1024
    private const val TAG_SIZE = 16
    private const val ENC_CHUNK_SIZE = CHUNK_SIZE + TAG_SIZE
    private const val X25519_INFO = "age-encryption.org/v1/X25519"
    private const val RECIPIENT_HRP = "age"
    private const val IDENTITY_HRP = "age-secret-key-"

    /** An age X25519 keypair. [secret] is `AGE-SECRET-KEY-1…`, [recipient] is `age1…`. */
    data class AgeIdentity(val secret: String, val recipient: String)

    /** Any failure parsing or decrypting an age file (bad format, bad MAC, no matching identity, corrupt payload). */
    class AgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val b64Encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    // ── Identity generation ────────────────────────────────────────────────

    fun generateIdentity(random: SecureRandom = SecureRandom()): AgeIdentity {
        val priv = X25519PrivateKeyParameters(random)
        val pub = priv.generatePublicKey()
        val recipient = Bech32.encode(RECIPIENT_HRP, pub.encoded)
        val secret = Bech32.encode(IDENTITY_HRP, priv.encoded).uppercase()
        return AgeIdentity(secret, recipient)
    }

    /** Derive the `age1…` recipient for an `AGE-SECRET-KEY-1…` identity (matches `age-keygen -y`). */
    fun recipientFor(identity: String): String {
        val scalar = parseIdentity(identity)
        val pub = X25519PrivateKeyParameters(scalar, 0).generatePublicKey()
        return Bech32.encode(RECIPIENT_HRP, pub.encoded)
    }

    // ── Encrypt ─────────────────────────────────────────────────────────────

    /**
     * Encrypt [input] to [output] for every recipient in [recipients] (each an
     * `age1…` string). Streams the payload — safe for arbitrarily large files.
     * Caller owns both streams and must close them.
     */
    fun encrypt(
        input: InputStream,
        output: OutputStream,
        recipients: List<String>,
        random: SecureRandom = SecureRandom(),
    ) {
        require(recipients.isNotEmpty()) { "at least one recipient required" }
        val fileKey = ByteArray(FILE_KEY_SIZE).also { random.nextBytes(it) }

        val header = StringBuilder(VERSION_LINE).append('\n')
        for (recipient in recipients) {
            val recipientPub = parseRecipient(recipient)
            val ephemeral = X25519PrivateKeyParameters(random)
            val ephemeralShare = ephemeral.generatePublicKey().encoded
            val shared = agree(ephemeral.encoded, recipientPub)
            val wrapKey = hkdf(shared, ephemeralShare + recipientPub, X25519_INFO)
            val body = aeadSeal(wrapKey, ByteArray(12), fileKey)
            header.append("-> ").append("X25519").append(' ').append(b64(ephemeralShare)).append('\n')
            header.append(wrapBody(b64(body))).append('\n')
        }
        header.append("---")
        val headerBytes = header.toString().toByteArray(Charsets.US_ASCII)
        val mac = hmacSha256(hkdf(fileKey, ByteArray(0), "header"), headerBytes)
        output.write(headerBytes)
        output.write(" ".toByteArray(Charsets.US_ASCII))
        output.write(b64(mac).toByteArray(Charsets.US_ASCII))
        output.write('\n'.code)

        val nonce = ByteArray(16).also { random.nextBytes(it) }
        output.write(nonce)
        streamEncrypt(hkdf(fileKey, nonce, "payload"), input, output)
    }

    // ── Decrypt ───────────────────────────────────────────────────────────

    /**
     * Decrypt [input] to [output] using the first of [identities] (each an
     * `AGE-SECRET-KEY-1…` string) whose recipient stanza unwraps. Throws
     * [AgeException] on any malformed input, bad MAC, corrupt payload, or when
     * no identity matches. Streams the payload.
     */
    fun decrypt(
        input: InputStream,
        output: OutputStream,
        identities: List<String>,
    ) {
        require(identities.isNotEmpty()) { "at least one identity required" }
        val scalars = identities.map { parseIdentity(it) }
        val src = if (input is BufferedInputStream) input else BufferedInputStream(input)

        val reader = LineReader(src)
        if (reader.readLine() != VERSION_LINE) throw AgeException("unsupported version")

        val stanzas = ArrayList<Stanza>()
        lateinit var mac: ByteArray
        var macInput: ByteArray? = null
        while (true) {
            val mark = reader.consumedSize()
            val line = reader.readLine()
            if (line.startsWith("---")) {
                if (!line.startsWith("--- ")) throw AgeException("malformed HMAC line")
                val macField = line.substring(4)
                if (macField.isEmpty() || macField.contains(' ')) throw AgeException("malformed HMAC line")
                mac = b64decode(macField)
                if (mac.size != 32) throw AgeException("bad HMAC length")
                macInput = reader.consumedPrefix(mark) + "---".toByteArray(Charsets.US_ASCII)
                break
            }
            stanzas.add(parseStanza(line, reader))
        }

        val fileKey = unwrapFileKey(stanzas, scalars)
            ?: throw AgeException("no matching identity")

        val expectedMac = hmacSha256(hkdf(fileKey, ByteArray(0), "header"), macInput)
        if (!MessageDigest.isEqual(expectedMac, mac)) throw AgeException("HMAC mismatch")

        val nonce = ByteArray(16)
        if (readFully(src, nonce) != 16) throw AgeException("missing payload nonce")
        streamDecrypt(hkdf(fileKey, nonce, "payload"), PushbackInputStream(src, 1), output)
    }

    // ── Header parsing ──────────────────────────────────────────────────────

    private class Stanza(val args: List<String>, val body: ByteArray)

    private fun parseStanza(firstLine: String, reader: LineReader): Stanza {
        if (!firstLine.startsWith("-> ")) throw AgeException("malformed stanza")
        val args = firstLine.substring(3).split(' ')
        if (args.isEmpty() || args.any { it.isEmpty() || it.any { c -> c.code !in 33..126 } }) {
            throw AgeException("malformed stanza arguments")
        }
        val body = StringBuilder()
        while (true) {
            val line = reader.readLine()
            if (line.length > 64) throw AgeException("stanza body line too long")
            body.append(line)
            if (line.length < 64) break
        }
        return Stanza(args, b64decode(body.toString()))
    }

    /** Try every X25519 stanza against every identity. Returns the 16-byte file key or null (no match). */
    private fun unwrapFileKey(stanzas: List<Stanza>, scalars: List<ByteArray>): ByteArray? {
        var sawRecipient = false
        for (stanza in stanzas) {
            if (stanza.args[0] != "X25519") continue // ignore unknown ("grease") stanzas
            sawRecipient = true
            if (stanza.args.size != 2) throw AgeException("X25519 stanza needs exactly one argument")
            val share = b64decode(stanza.args[1])
            if (share.size != 32) throw AgeException("X25519 share must be 32 bytes")
            for (scalar in scalars) {
                val shared = agree(scalar, share) // throws on all-zero (low-order point)
                val pub = X25519PrivateKeyParameters(scalar, 0).generatePublicKey().encoded
                val wrapKey = hkdf(shared, share + pub, X25519_INFO)
                val fileKey = try {
                    aeadOpen(wrapKey, ByteArray(12), stanza.body)
                } catch (_: Exception) {
                    continue // tag mismatch — this identity isn't the recipient
                }
                if (fileKey.size != FILE_KEY_SIZE) throw AgeException("bad file key size")
                return fileKey
            }
        }
        if (!sawRecipient) throw AgeException("no X25519 recipients")
        return null
    }

    /** Reads `\n`-terminated ASCII lines from [src], rejecting `\r`, while tracking the raw bytes consumed. */
    private class LineReader(private val src: InputStream) {
        private val consumed = java.io.ByteArrayOutputStream()
        fun consumedSize(): Int = consumed.size()
        fun consumedPrefix(n: Int): ByteArray = consumed.toByteArray().copyOf(n)

        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val b = src.read()
                if (b == -1) throw AgeException("unexpected end of header")
                consumed.write(b)
                if (b == '\n'.code) break
                if (b == '\r'.code) throw AgeException("CR in header")
                line.append(b.toChar())
            }
            return line.toString()
        }
    }

    // ── STREAM payload ──────────────────────────────────────────────────────

    private fun streamEncrypt(streamKey: ByteArray, input: InputStream, output: OutputStream) {
        val pin = PushbackInputStream(input, 1)
        val buf = ByteArray(CHUNK_SIZE)
        var counter = 0L
        while (true) {
            val n = readFully(pin, buf)
            val last = n < CHUNK_SIZE || peekEof(pin)
            output.write(aeadSeal(streamKey, streamNonce(counter, last), buf.copyOf(n)))
            if (last) break
            counter++
        }
    }

    private fun streamDecrypt(streamKey: ByteArray, pin: PushbackInputStream, output: OutputStream) {
        val buf = ByteArray(ENC_CHUNK_SIZE)
        var counter = 0L
        var firstChunk = true
        while (true) {
            val n = readFully(pin, buf)
            val last = n < ENC_CHUNK_SIZE || peekEof(pin)
            if (n == 0 && !firstChunk) throw AgeException("missing final chunk")
            val plain = try {
                aeadOpen(streamKey, streamNonce(counter, last), buf.copyOf(n))
            } catch (e: Exception) {
                throw AgeException("payload authentication failed", e)
            }
            if (plain.isEmpty() && !firstChunk) throw AgeException("empty final chunk")
            output.write(plain)
            if (last) break
            counter++
            firstChunk = false
        }
    }

    /** STREAM nonce: 11-byte big-endian counter followed by a 1-byte last-chunk flag. */
    private fun streamNonce(counter: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        var c = counter
        for (i in 10 downTo 0) {
            nonce[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        if (last) nonce[11] = 1
        return nonce
    }

    private fun peekEof(pin: PushbackInputStream): Boolean {
        val b = pin.read()
        if (b == -1) return true
        pin.unread(b)
        return false
    }

    // ── Primitives (BouncyCastle) ─────────────────────────────────────────────

    private fun agree(scalar: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(scalar, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        if (out.all { it == 0.toByte() }) throw AgeException("all-zero X25519 shared secret")
        return out
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { generator.generateBytes(it, 0, it.size) }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        return ByteArray(hmac.macSize).also { hmac.doFinal(it, 0) }
    }

    private fun aeadSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val written = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, written)
        return out
    }

    private fun aeadOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val written = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        val finished = cipher.doFinal(out, written)
        return out.copyOf(written + finished)
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private fun parseRecipient(recipient: String): ByteArray {
        val (hrp, data) = try {
            Bech32.decode(recipient)
        } catch (e: IllegalArgumentException) {
            throw AgeException("invalid recipient", e)
        }
        if (hrp != RECIPIENT_HRP || data.size != 32) throw AgeException("invalid recipient")
        return data
    }

    private fun parseIdentity(identity: String): ByteArray {
        val (hrp, data) = try {
            Bech32.decode(identity.trim())
        } catch (e: IllegalArgumentException) {
            throw AgeException("invalid identity", e)
        }
        if (hrp != IDENTITY_HRP || data.size != 32) throw AgeException("invalid identity")
        return data
    }

    private fun b64(bytes: ByteArray): String = b64Encoder.encodeToString(bytes)

    /** Strict canonical, unpadded base64 decode (age rejects padding and non-canonical encodings). */
    private fun b64decode(s: String): ByteArray {
        if (s.contains('=')) throw AgeException("base64 padding not allowed")
        val decoded = try {
            b64Decoder.decode(s)
        } catch (e: IllegalArgumentException) {
            throw AgeException("invalid base64", e)
        }
        if (b64Encoder.encodeToString(decoded) != s) throw AgeException("non-canonical base64")
        return decoded
    }

    /** Wrap a base64 body to 64-char lines; the final (possibly empty) line is always < 64 chars. */
    private fun wrapBody(b64: String): String {
        val sb = StringBuilder()
        var i = 0
        while (b64.length - i >= 64) {
            sb.append(b64, i, i + 64).append('\n')
            i += 64
        }
        sb.append(b64, i, b64.length)
        return sb.toString()
    }

    private fun readFully(src: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = src.read(buf, off, buf.size - off)
            if (r == -1) break
            off += r
        }
        return off
    }
}
