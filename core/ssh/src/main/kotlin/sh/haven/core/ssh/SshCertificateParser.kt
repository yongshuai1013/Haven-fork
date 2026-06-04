package sh.haven.core.ssh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/**
 * Parses OpenSSH user certificate files (`-cert.pub`) and extracts metadata
 * for validation and display.
 *
 * Wire format (RFC draft-miller-ssh-cert):
 *   string  cert_type (e.g. "ssh-ed25519-cert-v01@openssh.com")
 *   string  nonce
 *   ...key-specific fields...
 *   uint64  serial
 *   uint32  type (1=user, 2=host)
 *   string  key_id
 *   string  valid_principals (packed list)
 *   uint64  valid_after
 *   uint64  valid_before
 *   string  critical_options
 *   string  extensions
 *   string  reserved
 *   string  signature_key
 *   string  signature
 *
 * Used at attach time to validate the cert really belongs to the chosen
 * key (via [matchesKey]) and to surface principals / validity to the user.
 */
object SshCertificateParser {

    private const val CERT_SUFFIX = "-cert-v01@openssh.com"

    data class CertificateInfo(
        val certKeyType: String,
        val serial: Long,
        val keyId: String,
        val validPrincipals: List<String>,
        val validAfter: Long,
        val validBefore: Long,
        val rawBlob: ByteArray,
        val embeddedPublicKeyFingerprint: String,
    )

    /** Parse a `-cert.pub` file (text format: "type base64 [comment]"). Returns null if not a cert. */
    fun parse(fileBytes: ByteArray): CertificateInfo? {
        val text = fileBytes.decodeToString().trim()
        val parts = text.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val certKeyType = parts[0]
        if (!certKeyType.endsWith(CERT_SUFFIX)) return null

        val blob = try {
            Base64.getDecoder().decode(parts[1])
        } catch (_: Exception) {
            return null
        }

        return parseBlob(certKeyType, blob)
    }

    /** Cheap check: does the file look like an SSH cert? Used before invoking the full parser. */
    fun isCertificateFile(fileBytes: ByteArray): Boolean {
        val text = fileBytes.decodeToString().trim()
        val firstSpace = text.indexOf(' ')
        if (firstSpace <= 0) return false
        return text.substring(0, firstSpace).endsWith(CERT_SUFFIX)
    }

    /** Strip `-cert-v01@openssh.com` to get the base key type. */
    fun getBaseKeyType(certKeyType: String): String =
        if (certKeyType.endsWith(CERT_SUFFIX)) certKeyType.removeSuffix(CERT_SUFFIX) else certKeyType

    /** Append `-cert-v01@openssh.com` if not already present. */
    fun getCertKeyType(baseKeyType: String): String =
        if (baseKeyType.endsWith(CERT_SUFFIX)) baseKeyType else "$baseKeyType$CERT_SUFFIX"

    /**
     * Render a stored certificate as an OpenSSH public-key **text line** —
     * `"<cert-type> <base64-blob>"`. JSch's
     * `addIdentity(name, prv, pubkey, passphrase)` parses its `pubkey`
     * argument as this textual form (type + base64), not as the raw binary;
     * passing the binary blob makes JSch silently fall back to the bare
     * public key and the server rejects a CA-only login (#185). The cert
     * type is read from the blob's leading SSH `string` field so we don't
     * depend on a separately-tracked key type.
     *
     * The input is first run through [normalizeToBinaryBlob] because
     * `SshKey.certificateBytes` has been persisted in more than one shape:
     * the manual "Attach certificate" path stores the decoded binary, but
     * the step-ca "Generate via step-ca" path stored the *base64 text* of
     * step-ca's `/1.0/sign-ssh` `crt` field verbatim (v5.24.88–v5.59.x),
     * which made every step-ca key fail here — and on export — with
     * "invalid certificate type length". (#133/#185)
     */
    fun toOpenSshPublicKeyLine(storedBytes: ByteArray): ByteArray {
        val blob = normalizeToBinaryBlob(storedBytes)
        require(blob.size >= 4) { "certificate blob too short" }
        val typeLen = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        require(typeLen in 1..(blob.size - 4)) { "invalid certificate type length" }
        val type = String(blob, 4, typeLen, Charsets.US_ASCII)
        val b64 = Base64.getEncoder().encodeToString(blob)
        return "$type $b64".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Coerce stored certificate bytes to the raw binary OpenSSH wire blob.
     * `SshKey.certificateBytes` ships in three shapes; normalise them all to
     * the decoded binary the rest of this object expects:
     *  - raw binary (manual "Attach certificate" path) — returned unchanged
     *  - bare standard-base64 of the blob, no type prefix — the shape of
     *    step-ca's `/1.0/sign-ssh` `crt` field, stored verbatim by the
     *    pre-fix "Generate via step-ca" path (#133/#185)
     *  - a full OpenSSH text line `"<cert-type> <base64> [comment]"`
     *
     * Mirrors the tolerance already in [OpenSshCertificate.parse]. Input that
     * matches none of the above is returned unchanged so the caller still
     * surfaces its own diagnostic (e.g. the "invalid certificate type
     * length" require in [toOpenSshPublicKeyLine]).
     */
    internal fun normalizeToBinaryBlob(bytes: ByteArray): ByteArray {
        if (looksLikeBinaryCert(bytes)) return bytes
        val text = runCatching { bytes.decodeToString().trim() }.getOrDefault("")
        if (text.isEmpty()) return bytes
        val b64 = if (text.any { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
            val parts = text.split(Regex("\\s+"), limit = 3)
            if (parts.size >= 2 && parts[0].endsWith(CERT_SUFFIX)) parts[1] else return bytes
        } else {
            text
        }
        val decoded = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return bytes
        return if (looksLikeBinaryCert(decoded)) decoded else bytes
    }

    /** True when [blob] is the raw binary wire form: a leading SSH `string`
     * whose value is a `*-cert-v01@openssh.com` type. Base64 text never
     * matches — its first byte is a printable ASCII char, never the `0x00`
     * a small big-endian length needs. */
    private fun looksLikeBinaryCert(blob: ByteArray): Boolean {
        if (blob.size < 4) return false
        val typeLen = ((blob[0].toInt() and 0xFF) shl 24) or
            ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or
            (blob[3].toInt() and 0xFF)
        if (typeLen !in 1..(blob.size - 4)) return false
        val type = runCatching { String(blob, 4, typeLen, Charsets.US_ASCII) }.getOrNull()
            ?: return false
        return type.endsWith(CERT_SUFFIX)
    }

    /** True when the cert's embedded public key matches the supplied SHA-256 fingerprint. */
    fun matchesKey(cert: CertificateInfo, keyFingerprintSha256: String): Boolean =
        cert.embeddedPublicKeyFingerprint == keyFingerprintSha256

    /** True when the cert is currently within its validity window. `0` / `-1L` (forever) treated as unbounded. */
    fun isCurrentlyValid(cert: CertificateInfo): Boolean {
        val now = System.currentTimeMillis() / 1000
        val afterOk = cert.validAfter == 0L || now >= cert.validAfter
        val beforeOk = cert.validBefore == 0L ||
            cert.validBefore == -1L ||
            now <= cert.validBefore
        return afterOk && beforeOk
    }

    private fun parseBlob(certKeyType: String, blob: ByteArray): CertificateInfo? {
        val buf = ByteBuffer.wrap(blob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            val encodedType = readString(buf)
            if (encodedType != certKeyType) return null

            // Nonce — read and discard.
            readBytes(buf)

            // Key-specific fields are needed twice: once to reconstruct the
            // public-key blob (for fingerprinting), once to advance the
            // cursor past them so we can read serial/type/principals next.
            val publicKeyBlob = extractPublicKeyBlob(certKeyType, blob)
            skipKeyFields(buf, certKeyType)

            val serial = buf.long
            @Suppress("UNUSED_VARIABLE")
            val type = buf.int  // 1=user, 2=host — we accept both, the auth path won't accept hosts anyway.
            val keyId = readString(buf)
            val principalsBlob = readBytes(buf)
            val principals = parsePrincipalsList(principalsBlob)
            val validAfter = buf.long
            val validBefore = buf.long

            val fingerprint = if (publicKeyBlob != null) {
                val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
                "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
            } else ""

            return CertificateInfo(
                certKeyType = certKeyType,
                serial = serial,
                keyId = keyId,
                validPrincipals = principals,
                validAfter = validAfter,
                validBefore = validBefore,
                rawBlob = blob,
                embeddedPublicKeyFingerprint = fingerprint,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Reconstruct the SSH wire-format public key blob (type + key data, the
     * same shape that appears in `authorized_keys`) so we can fingerprint it
     * and compare against the [SshKey.fingerprintSha256].
     */
    private fun extractPublicKeyBlob(certKeyType: String, certBlob: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(certBlob)
        buf.order(ByteOrder.BIG_ENDIAN)

        try {
            readBytes(buf) // cert type
            readBytes(buf) // nonce

            // Reconstruct the authorized_keys wire form: the base key type
            // string followed by the algorithm-specific fields. SK types use a
            // distinct base name (`...@openssh.com`) and include the trailing
            // `application` field in the public key. (#208 finding 12)
            val pubKeyBuf = java.io.ByteArrayOutputStream()
            when (certKeyType) {
                "ssh-ed25519-cert-v01@openssh.com" -> {
                    writeBytes(pubKeyBuf, "ssh-ed25519".toByteArray())
                    writeBytes(pubKeyBuf, readBytes(buf)) // pk
                }
                "sk-ssh-ed25519-cert-v01@openssh.com" -> {
                    writeBytes(pubKeyBuf, "sk-ssh-ed25519@openssh.com".toByteArray())
                    writeBytes(pubKeyBuf, readBytes(buf)) // pk
                    writeBytes(pubKeyBuf, readBytes(buf)) // application
                }
                "ssh-rsa-cert-v01@openssh.com" -> {
                    writeBytes(pubKeyBuf, "ssh-rsa".toByteArray())
                    writeBytes(pubKeyBuf, readBytes(buf)) // e
                    writeBytes(pubKeyBuf, readBytes(buf)) // n
                }
                "ssh-dss-cert-v01@openssh.com" -> {
                    writeBytes(pubKeyBuf, "ssh-dss".toByteArray())
                    repeat(4) { writeBytes(pubKeyBuf, readBytes(buf)) } // p,q,g,y
                }
                "sk-ecdsa-sha2-nistp256-cert-v01@openssh.com" -> {
                    writeBytes(pubKeyBuf, "sk-ecdsa-sha2-nistp256@openssh.com".toByteArray())
                    writeBytes(pubKeyBuf, readBytes(buf)) // curve
                    writeBytes(pubKeyBuf, readBytes(buf)) // Q
                    writeBytes(pubKeyBuf, readBytes(buf)) // application
                }
                else -> {
                    if (certKeyType.startsWith("ecdsa-sha2-") && certKeyType.endsWith(CERT_SUFFIX)) {
                        writeBytes(pubKeyBuf, getBaseKeyType(certKeyType).toByteArray())
                        writeBytes(pubKeyBuf, readBytes(buf)) // curve
                        writeBytes(pubKeyBuf, readBytes(buf)) // Q
                    } else {
                        return null
                    }
                }
            }
            return pubKeyBuf.toByteArray()
        } catch (_: Exception) {
            return null
        }
    }

    private fun skipKeyFields(buf: ByteBuffer, certKeyType: String) {
        // Match the exact type, not substrings: the FIDO/SK types
        // (`sk-ssh-ed25519`, `sk-ecdsa-…`) also *contain* "ed25519"/"ecdsa" but
        // carry an extra trailing `application` string. Missing it leaves the
        // cursor one wire-string short and misaligns every field after the key
        // (serial, type, keyId, principals, validity). (#208 finding 12)
        when (certKeyType) {
            "ssh-ed25519-cert-v01@openssh.com" -> {
                readBytes(buf) // public key (32 bytes)
            }
            "sk-ssh-ed25519-cert-v01@openssh.com" -> {
                readBytes(buf) // public key
                readBytes(buf) // application (SK extra field)
            }
            "ssh-rsa-cert-v01@openssh.com" -> {
                readBytes(buf) // e
                readBytes(buf) // n
            }
            "ssh-dss-cert-v01@openssh.com" -> {
                readBytes(buf) // p
                readBytes(buf) // q
                readBytes(buf) // g
                readBytes(buf) // y
            }
            "sk-ecdsa-sha2-nistp256-cert-v01@openssh.com" -> {
                readBytes(buf) // curve name
                readBytes(buf) // EC point
                readBytes(buf) // application (SK extra field)
            }
            else -> {
                if (certKeyType.startsWith("ecdsa-sha2-") && certKeyType.endsWith(CERT_SUFFIX)) {
                    readBytes(buf) // curve name
                    readBytes(buf) // EC point
                }
            }
        }
    }

    private fun parsePrincipalsList(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val buf = ByteBuffer.wrap(data)
        buf.order(ByteOrder.BIG_ENDIAN)
        val result = mutableListOf<String>()
        while (buf.hasRemaining()) {
            result.add(readString(buf))
        }
        return result
    }

    private fun readString(buf: ByteBuffer): String = String(readBytes(buf))

    private fun readBytes(buf: ByteBuffer): ByteArray {
        val len = buf.int
        require(len in 0..buf.remaining()) { "Invalid length: $len (remaining: ${buf.remaining()})" }
        val data = ByteArray(len)
        buf.get(data)
        return data
    }

    private fun writeBytes(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putInt(data.size)
        out.write(lenBuf.array())
        out.write(data)
    }
}
