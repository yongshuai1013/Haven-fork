package sh.haven.core.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Covers the CTAP2 authenticatorMakeCredential request encoding, the setPIN
 * encoder, and the MakeCredential response / authData parsing added for
 * on-device SSH-SK key registration (multiple-YubiKey enrolment).
 *
 * The MakeCredential reply carries the new credential's handle + COSE public
 * key inside authenticatorData; getting the attestedCredentialData offsets
 * wrong would silently produce a corrupt SSH key, so the authData parse is the
 * load-bearing test here.
 */
class Ctap2MakeCredentialTest {

    @Test
    fun `makeCredential resident without UV is a map of 5`() {
        val cmd = Ctap2Cbor.encodeMakeCredentialCommand(
            clientDataHash = ByteArray(32) { it.toByte() },
            rpId = "ssh:",
            rpName = "SSH",
            userId = ByteArray(32) { (it * 7).toByte() },
            userName = "haven",
            userDisplayName = "Haven SSH",
            algorithms = listOf(Ctap2Cbor.COSE_ALG_EDDSA),
            residentKey = true,
        )
        assertEquals(Ctap2Cbor.CMD_MAKE_CREDENTIAL, cmd[0])
        assertEquals(0xA5.toByte(), cmd[1]) // map(5): cdh, rp, user, params, options
    }

    @Test
    fun `makeCredential with UV carries pinUvAuthParam keys 8 and 9 (map of 7)`() {
        val cmd = Ctap2Cbor.encodeMakeCredentialCommand(
            clientDataHash = ByteArray(32),
            rpId = "ssh:",
            rpName = "SSH",
            userId = ByteArray(16),
            userName = "haven",
            userDisplayName = "Haven SSH",
            algorithms = listOf(Ctap2Cbor.COSE_ALG_EDDSA),
            residentKey = true,
            pinUvAuthParam = ByteArray(16) { 0x42 },
            pinUvAuthProtocol = 2,
        )
        assertEquals(Ctap2Cbor.CMD_MAKE_CREDENTIAL, cmd[0])
        assertEquals(0xA7.toByte(), cmd[1]) // map(7)
    }

    @Test
    fun `setPIN encoder emits subCommand 3 with keyAgreement, authParam, newPinEnc`() {
        val pair = Ctap2PinProtocol.V2.generateEphemeralKeyPair()
        val (x, y) = Ctap2PinProtocol.V2.ecPublicToCoseCoords(
            pair.public as java.security.interfaces.ECPublicKey,
        )
        val cmd = Ctap2Cbor.encodeClientPinSetPin(
            protocol = 2,
            platformKeyAgreement = Ctap2Cbor.CoseEcdhPubKey(x, y),
            pinUvAuthParam = ByteArray(32) { 0x11 },
            newPinEnc = ByteArray(64) { 0x22 },
        )
        assertEquals(Ctap2Cbor.CMD_CLIENT_PIN, cmd[0])
        assertEquals(0xA5.toByte(), cmd[1]) // map(5)
        // bytes: { 0xA5, 0x01, 0x02(protocol), 0x02, <sub> ... }
        assertEquals(
            "subCommand byte must be 3 (setPIN)",
            Ctap2Cbor.PIN_SUB_SET_PIN.toByte(), cmd[5],
        )
    }

    @Test
    fun `decode makeCredential response extracts authData`() {
        val authData = ByteArray(80) { it.toByte() }
        val out = ByteArrayOutputStream().apply {
            write(0xA2)                                   // map(2)
            write(0x01)                                   // key 1 (fmt)
            write(0x64); write("none".toByteArray())      // text(4) "none"
            write(0x02)                                   // key 2 (authData)
            write(0x58); write(authData.size); write(authData) // bstr(80)
        }
        val resp = Ctap2Cbor.decodeMakeCredentialResponse(out.toByteArray())
        assertEquals("none", resp.fmt)
        assertArrayEquals(authData, resp.authData)
    }

    @Test
    fun `parse attestedCredentialData extracts handle and ed25519 pubkey`() {
        val edPub = ByteArray(32) { (0x30 + it).toByte() }
        val credId = ByteArray(16) { (0x90 + it).toByte() }
        val authData = ByteArrayOutputStream().apply {
            write(ByteArray(32) { 0x01 })       // rpIdHash
            write(0x41)                         // flags: AT(0x40) | UP(0x01)
            write(byteArrayOf(0, 0, 0, 5))      // signCount = 5
            write(ByteArray(16) { 0x02 })       // aaguid
            write(byteArrayOf(0x00, credId.size.toByte())) // credIdLen = 16, big-endian
            write(credId)
            // COSE Ed25519 key: map(4) { 1:1 (OKP), 3:-8 (EdDSA), -1:6 (crv), -2: bstr(32) }
            write(0xA4)
            write(0x01); write(0x01)
            write(0x03); write(0x27)            // 3: -8
            write(0x20); write(0x06)            // -1: 6
            write(0x21); write(0x58); write(0x20); write(edPub) // -2: bstr(32)
        }.toByteArray()

        val cred = Ctap2Cbor.parseAttestedCredentialData(authData)
        assertArrayEquals(credId, cred.credentialId)
        assertEquals(5, cred.signCount)
        assertTrue(cred.publicKey is Ctap2Cbor.CosePublicKey.Ed25519)
        assertArrayEquals(edPub, (cred.publicKey as Ctap2Cbor.CosePublicKey.Ed25519).rawKey)
    }

    @Test
    fun `parse attestedCredentialData rejects authData without the AT flag`() {
        val authData = ByteArray(60).apply { this[32] = 0x01 } // UP only, AT bit unset
        try {
            Ctap2Cbor.parseAttestedCredentialData(authData)
            fail("expected IllegalArgumentException for missing AT flag")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("AT flag"))
        }
    }
}
