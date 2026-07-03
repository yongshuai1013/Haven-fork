package sh.haven.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering guard for the "Any hardware key" up-front detection prompt.
 *
 * With 2+ enrolled hardware keys, "Any hardware key" resolves to an either-of
 * pool and [SshClient.applyFidoAuth] used to run a blocking "present your key"
 * prompt during connect setup — even when the pool was a *secondary* fallback
 * behind a primary software key that would authenticate on its own. That made
 * the hardware key appear to be "chosen ahead of" the primary software key.
 * [SshClient.anyHardwareKeyDetectionShouldDefer] decides when to skip that
 * prompt (pool still registered as a fallback).
 */
class SshClientAnyHardwareKeyDeferTest {
    private fun fido() = ConnectionConfig.AuthMethod.FidoKey(skKeyData = ByteArray(0), anyOf = true)
    private fun softwareBundle() = ConnectionConfig.AuthMethod.PrivateKeys(keys = emptyList())
    private fun softwareKey() = ConnectionConfig.AuthMethod.PrivateKey(keyBytes = ByteArray(0))
    private fun password() = ConnectionConfig.AuthMethod.Password("pw")

    @Test
    fun `defers when an any-software-key bundle precedes the hardware pool`() {
        // The reported config: [any software key, any hardware key (pool of 2+)].
        assertTrue(
            SshClient.anyHardwareKeyDetectionShouldDefer(
                listOf(softwareBundle(), fido(), fido()),
            ),
        )
    }

    @Test
    fun `defers when a single software key precedes the hardware pool`() {
        assertTrue(
            SshClient.anyHardwareKeyDetectionShouldDefer(
                listOf(softwareKey(), fido(), fido()),
            ),
        )
    }

    @Test
    fun `does not defer when the hardware pool is the primary method`() {
        // "Any hardware key" alone — keep the #237 present-your-key detection.
        assertFalse(
            SshClient.anyHardwareKeyDetectionShouldDefer(listOf(fido(), fido())),
        )
    }

    @Test
    fun `does not defer when the hardware pool precedes the software key`() {
        // User explicitly ordered hardware first — detecting up front is correct.
        assertFalse(
            SshClient.anyHardwareKeyDetectionShouldDefer(
                listOf(fido(), fido(), softwareBundle()),
            ),
        )
    }

    @Test
    fun `a preceding password does not count as a preceding publickey`() {
        // publickey is always offered before password, so the pool is still the
        // first publickey the server reaches.
        assertFalse(
            SshClient.anyHardwareKeyDetectionShouldDefer(
                listOf(password(), fido(), fido()),
            ),
        )
    }

    @Test
    fun `no hardware key means nothing to defer`() {
        assertFalse(
            SshClient.anyHardwareKeyDetectionShouldDefer(listOf(softwareBundle())),
        )
    }
}
