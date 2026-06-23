package sh.haven.core.security

/**
 * Bech32 (BIP-173, checksum constant 1 — *not* Bech32m) encode/decode, the
 * encoding age uses for recipients and identities:
 *
 *  - recipient: `Bech32.encode("age", x25519PublicKey)`        → `age1…`
 *  - identity:  `Bech32.encode("AGE-SECRET-KEY-", scalar)`     → `AGE-SECRET-KEY-1…`
 *    (age uppercases the identity; the checksum is computed over the
 *    lowercase form, so [encode] lowercases the HRP for the checksum and
 *    callers uppercase the whole string.)
 *
 * Only what age needs: a single HRP, 32-byte payloads, no length cap games
 * beyond the BIP-173 90-char limit (age strings are well under it). This is
 * a checksummed base32 codec, not cryptography.
 */
internal object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val REVERSE: IntArray = IntArray(128) { -1 }.also { rev ->
        CHARSET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    /** Encode [data] (8-bit bytes) under human-readable prefix [hrp]. HRP is used as given. */
    fun encode(hrp: String, data: ByteArray): String {
        val values = convertBits(data, 8, 5, pad = true)
            ?: throw IllegalArgumentException("bech32: cannot regroup bits")
        val checksum = createChecksum(hrp, values)
        val sb = StringBuilder(hrp).append('1')
        (values + checksum).forEach { sb.append(CHARSET[it]) }
        return sb.toString()
    }

    /**
     * Decode a Bech32 string into (hrp, 8-bit data). Accepts all-upper or
     * all-lower (mixed case is rejected per BIP-173); the returned HRP is
     * lowercased. Throws [IllegalArgumentException] on any malformed input.
     */
    fun decode(input: String): Pair<String, ByteArray> {
        require(input.length in 8..90) { "bech32: bad length" }
        val hasLower = input.any { it in 'a'..'z' }
        val hasUpper = input.any { it in 'A'..'Z' }
        require(!(hasLower && hasUpper)) { "bech32: mixed case" }
        val s = input.lowercase()
        val sep = s.lastIndexOf('1')
        require(sep >= 1 && sep + 7 <= s.length) { "bech32: bad separator" }
        val hrp = s.substring(0, sep)
        val dataPart = s.substring(sep + 1)
        val values = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val v = REVERSE[dataPart[i].code]
            require(v != -1) { "bech32: bad char" }
            values[i] = v
        }
        require(verifyChecksum(hrp, values)) { "bech32: bad checksum" }
        val payload = convertBits(
            values.copyOfRange(0, values.size - 6).map { it.toByte() }.toByteArray(),
            5, 8, pad = false,
        ) ?: throw IllegalArgumentException("bech32: bad padding")
        return hrp to ByteArray(payload.size) { payload[it].toByte() }
    }

    private fun polymod(values: IntArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor gen[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = hrp[i].code ushr 5
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = hrp[i].code and 31
        return out
    }

    private fun verifyChecksum(hrp: String, values: IntArray): Boolean =
        polymod(hrpExpand(hrp) + values) == 1

    private fun createChecksum(hrp: String, values: IntArray): IntArray {
        val enc = hrpExpand(hrp) + values + intArrayOf(0, 0, 0, 0, 0, 0)
        val mod = polymod(enc) xor 1
        return IntArray(6) { (mod ushr (5 * (5 - it))) and 31 }
    }

    /** Regroup [data] from [from]-bit to [to]-bit groups. Returns null if a non-pad remainder has set bits. */
    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            if ((value ushr from) != 0) return null
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (to - bits)) and maxv)
        } else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }
}
