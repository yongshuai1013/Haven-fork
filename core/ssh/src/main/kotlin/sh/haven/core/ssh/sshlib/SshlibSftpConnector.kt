package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient as SshlibClient
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshIoException
import java.util.Base64

/**
 * Dials a dedicated sshlib connection for SFTP (#58, phase 1).
 *
 * Capability-gated: [unsupportedReason] decides BEFORE dialing whether this
 * config can run on sshlib; a non-null reason means the caller falls back to
 * JSch and logs why. Dial/auth failures after that decision throw
 * [SshIoException] — they are real errors, never a silent fallback.
 *
 * Host keys are fail-closed: the sshlib connection only accepts a key that
 * Haven's [HostKeyVerifier] already reports as [HostKeyResult.Trusted]. The
 * interactive JSch connect that preceded this dial established TOFU trust,
 * so no prompting path is needed here.
 */
internal object SshlibSftpConnector {

    /**
     * Why this config cannot use the sshlib engine yet, or null when it can.
     * Phase-1 scope: direct-TCP dials with password auth, plain private keys
     * (unencrypted or passphrase-carrying), or the try-any-key pool without
     * OpenSSH certificates.
     */
    fun unsupportedReason(
        config: ConnectionConfig,
        hasJump: Boolean,
        hasProxy: Boolean,
    ): String? {
        if (hasJump) return "jump-host connections"
        if (hasProxy) return "proxied connections"
        return unsupportedAuthReason(config.authMethod)
    }

    private fun unsupportedAuthReason(method: ConnectionConfig.AuthMethod): String? = when (method) {
        is ConnectionConfig.AuthMethod.Password -> null
        is ConnectionConfig.AuthMethod.PrivateKey ->
            if (method.certificateBytes != null) "OpenSSH certificate auth" else null
        is ConnectionConfig.AuthMethod.PrivateKeys ->
            if (method.keys.any { it.certificateBytes != null }) "OpenSSH certificate auth" else null
        is ConnectionConfig.AuthMethod.FidoKey -> "FIDO2 hardware keys"
        // Multi-factor chains rely on server-driven partial success — untested
        // on sshlib; gate until the phase-2 capability spike proves it.
        is ConnectionConfig.AuthMethod.Multi -> "multi-method auth chains"
    }

    /**
     * Dial + authenticate a sshlib connection, returning the connected client.
     * The caller owns the connection and must [org.connectbot.sshlib.SshClient.disconnect]
     * it. Callers must have cleared [unsupportedReason] first.
     */
    suspend fun dialAndAuth(
        config: ConnectionConfig,
        hostKeyVerifier: HostKeyVerifier,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): SshlibClient {
        val host = SshClient.resolveHost(config.host, config.addressFamily)
        val trustGate = TrustedOnlyVerifier(hostKeyVerifier, config.host, config.port)
        val client = SshlibClient(
            org.connectbot.sshlib.SshClientConfig {
                this.host = host
                this.port = config.port
                this.hostKeyVerifier = trustGate
                // sshlib 0.3.1 client-initiated rekey is broken both ways: a
                // byte-limit rekey mid-transfer kills the channel, an interval
                // rekey wedges an idle session (SshlibCapabilitySpikeTest GAP
                // probes). Push both thresholds out of practical reach until
                // the upstream fix ships; the probes flip when it does.
                rekeyIntervalMs = Long.MAX_VALUE / 2
                rekeyBytesLimit = Long.MAX_VALUE / 2
            },
        )
        try {
            when (val result = withTimeout(connectTimeoutMs) { client.connect() }) {
                is ConnectResult.Success -> Unit
                is ConnectResult.HostKeyRejected -> throw SshIoException(
                    "sshlib: host key for ${config.host} is not in Haven's trusted known hosts",
                )
                is ConnectResult.AlgorithmMismatch ->
                    throw SshIoException("sshlib: algorithm negotiation failed: ${result.message}")
                is ConnectResult.TransportError ->
                    throw SshIoException("sshlib: connect failed: ${result.cause.message}", result.cause)
                is ConnectResult.ProtocolError ->
                    throw SshIoException("sshlib: protocol error: ${result.message}", result.cause)
            }
            authenticate(client, config)
            return client
        } catch (t: Throwable) {
            try { client.disconnect() } catch (_: Exception) { /* best effort */ }
            throw t
        }
    }

    /**
     * Dial + authenticate + open the SFTP subsystem. The returned session owns
     * the connection. Callers must have cleared [unsupportedReason] first.
     */
    suspend fun connect(
        config: ConnectionConfig,
        hostKeyVerifier: HostKeyVerifier,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): SshlibSftpSession {
        val client = dialAndAuth(config, hostKeyVerifier, connectTimeoutMs)
        try {
            val sftp = when (val r = client.openSftp()) {
                is org.connectbot.sshlib.SftpResult.Success -> r.value
                is org.connectbot.sshlib.SftpResult.ServerError ->
                    throw SshIoException("sshlib: SFTP subsystem rejected: ${r.message}")
                is org.connectbot.sshlib.SftpResult.ProtocolError ->
                    throw SshIoException("sshlib: SFTP open failed: ${r.message}")
                is org.connectbot.sshlib.SftpResult.IoError ->
                    throw SshIoException("sshlib: SFTP open failed: ${r.cause.message}", r.cause)
            }
            return SshlibSftpSession(client, sftp)
        } catch (t: Throwable) {
            try { client.disconnect() } catch (_: Exception) { /* best effort */ }
            throw t
        }
    }

    private suspend fun authenticate(client: SshlibClient, config: ConnectionConfig) {
        val username = config.username
        when (val method = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> {
                val password = String(method.password)
                var result = client.authenticatePassword(username, password)
                // Servers that only advertise keyboard-interactive still take
                // the same secret — answer every prompt with it, as JSch's
                // UserInfo path does for simple password-equivalent KI.
                if (result is AuthResult.Failure && "keyboard-interactive" in result.allowedMethods) {
                    result = client.authenticateKeyboardInteractive(
                        username,
                        object : KeyboardInteractiveCallback {
                            override suspend fun onInfoRequest(
                                name: String,
                                instruction: String,
                                prompts: List<KeyboardInteractiveCallback.Prompt>,
                                respond: suspend (responses: List<String>) -> Unit,
                            ) = respond(prompts.map { password })
                        },
                    )
                }
                result.requireSuccess()
            }
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                client.authenticatePublicKey(
                    username,
                    method.keyBytes,
                    method.passphrase.takeIf { it.isNotEmpty() }?.let { String(it) },
                ).requireSuccess()
            }
            is ConnectionConfig.AuthMethod.PrivateKeys -> {
                var last: AuthResult = AuthResult.Failure(emptySet())
                for (entry in method.keys) {
                    last = client.authenticatePublicKey(
                        username, entry.keyBytes, entry.passphrase?.let { String(it, Charsets.UTF_8) },
                    )
                    if (last is AuthResult.Success) break
                }
                last.requireSuccess()
            }
            // Both rejected by unsupportedReason before dialing.
            is ConnectionConfig.AuthMethod.FidoKey,
            is ConnectionConfig.AuthMethod.Multi,
            -> throw SshIoException("sshlib: unsupported auth method ${method::class.simpleName}")
        }
    }

    private fun AuthResult.requireSuccess() {
        when (this) {
            is AuthResult.Success -> Unit
            is AuthResult.Failure -> throw SshIoException(
                "sshlib: authentication failed (server allows: ${allowedMethods.joinToString()})",
            )
            is AuthResult.Error -> throw SshIoException("sshlib: authentication error: $message", cause)
        }
    }

    /** sshlib host-key gate that delegates to Haven's TOFU store, fail-closed. */
    private class TrustedOnlyVerifier(
        private val verifier: HostKeyVerifier,
        private val hostname: String,
        private val port: Int,
    ) : org.connectbot.sshlib.HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean {
            val entry = KnownHostEntry(
                hostname = hostname,
                port = port,
                keyType = key.type,
                publicKeyBase64 = Base64.getEncoder().encodeToString(key.encoded),
            )
            return verifier.verify(entry) is HostKeyResult.Trusted
        }
    }

    private const val CONNECT_TIMEOUT_MS = 30_000L
}
