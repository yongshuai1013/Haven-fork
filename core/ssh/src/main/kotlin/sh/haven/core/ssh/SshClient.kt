package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.fido.FidoIdentity
import sh.haven.core.fido.SkKeyData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer


private const val TAG = "SshClient"

data class ExecResult(
    val exitStatus: Int,
    val stdout: String,
    val stderr: String,
    /**
     * True when [SshClient.execCommand] aborted the channel at its
     * `timeoutMs` deadline. [exitStatus] is then meaningless (-1) and the
     * output may be truncated or empty (the forced channel close can tear
     * the pipe mid-read).
     */
    val timedOut: Boolean = false,
)

/**
 * Wrapper around JSch providing coroutine-based SSH connectivity.
 */
class SshClient : SshConnection {
    private val jsch = JSch()
    /** Set before connecting with a FidoKey auth method. */
    override var fidoAuthenticator: FidoAuthenticator? = null
    /** Set before connect() to capture verbose SSH protocol output. */
    override var verboseLogger: SshVerboseLogger? = null
    private var session: Session? = null

    /**
     * True when the most recent successful connect verified the host via a
     * trusted CA-signed host certificate instead of a per-host key (#133).
     * For logging/UI; the trust decision itself is conveyed by
     * [connect]/[connectBlocking] returning null.
     */
    @Volatile
    override var hostVerifiedByCa: Boolean = false
        private set

    /**
     * True when the last [connect]/[connectBlocking] dialed through a proxy
     * or jump chain rather than direct TCP. The sshlib SFTP engine (#58)
     * needs a direct path for its dedicated connection, so [SshSessionManager]
     * consults this before routing a profile's SFTP to sshlib.
     */
    @Volatile
    override var connectedViaProxy: Boolean = false
        private set

    /** Whether the active session should set agent forwarding on newly opened shell/exec channels. */
    private var agentForwardingEnabled = false

    override val isConnected: Boolean
        get() = session?.isConnected == true

    /** The underlying JSch session, for creating ProxyJump tunnels. */
    internal val jschSession: Session?
        get() = session

    /**
     * Bounded request/response liveness check. Returns false on a null /
     * disconnected session, on exception, or on timeout — the last case being
     * the one that matters: a socket that died silently in the background (no
     * RST reached us yet) still reports [isConnected] == true, so we force a
     * real round-trip over the transport and treat a stalled reply as dead.
     *
     * Bounds the wait with JSch's own [com.jcraft.jsch.Channel.connect] timeout
     * (the channel-open handshake needs a reply from the server) rather than an
     * outer [withTimeoutOrNull]: the latter cannot interrupt the blocking exec
     * I/O, so on a hard-dead socket detection slipped to ~20 s (verified on
     * device) instead of the intended bound. We also do NOT read command output
     * — the open confirmation alone proves the transport is answering — and we
     * deliberately avoid [Session.sendKeepAliveMsg], which only enqueues a write
     * and never waits for the reply, so it never observes a missing answer.
     */
    override suspend fun isAlive(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext false
        if (!sess.isConnected) return@withContext false
        runCatching {
            val channel = sess.openChannel("exec") as ChannelExec
            channel.setCommand("true")
            try {
                // Waits up to timeoutMs for the server's channel-open
                // confirmation; a silently-dead socket never answers and this
                // throws once the bound elapses.
                channel.connect(timeoutMs.toInt())
                true
            } finally {
                try { channel.disconnect() } catch (_: Throwable) { /* best effort */ }
            }
        }.getOrDefault(false)
    }

    /** Flatten a possibly-[ConnectionConfig.AuthMethod.Multi] into its leaves, in order. */
    private fun flattenAuth(m: ConnectionConfig.AuthMethod): List<ConnectionConfig.AuthMethod> =
        if (m is ConnectionConfig.AuthMethod.Multi) m.methods.flatMap(::flattenAuth) else listOf(m)

    /**
     * Register every credential in [config]'s auth method(s) on [sess]/[jsch],
     * flattening a [ConnectionConfig.AuthMethod.Multi] so a multi-factor chain
     * (`publickey,password`, …) has all its credentials available at once and
     * JSch can satisfy the server's partial-success sequence. Returns the
     * password to seed the keyboard-interactive fallback (last Password wins),
     * or null. Identity names are unique per call so re-adds across reconnects
     * don't collide in JSch's identity repository. (#166)
     */
    private fun applyAuthMethods(config: ConnectionConfig, sess: Session): CharArray? {
        var fallbackKiPassword: CharArray? = null
        val flat = flattenAuth(config.authMethod)
        for (auth in flat) {
            when (auth) {
                is ConnectionConfig.AuthMethod.Multi -> { /* flattened above */ }
                is ConnectionConfig.AuthMethod.Password -> {
                    sess.setPassword(charsToUtf8Bytes(auth.password))
                    // Silently satisfy single-prompt "Password:" KI rounds —
                    // servers that route the password through the
                    // keyboard-interactive channel shouldn't make the user
                    // retype a saved password.
                    fallbackKiPassword = auth.password
                }
                is ConnectionConfig.AuthMethod.PrivateKey -> {
                    // Pass the OpenSSH cert (when present) as the third
                    // public-key arg; JSch wraps it for CA validation. The
                    // stored cert is a raw binary blob, but JSch expects the
                    // textual `<type> <base64>` form — convert it. (#133/#185)
                    jsch.addIdentity(
                        "haven-key-${System.nanoTime()}",
                        auth.keyBytes,
                        auth.certificateBytes?.let { SshCertificateParser.toOpenSshPublicKeyLine(it) },
                        if (auth.passphrase.isNotEmpty()) charsToUtf8Bytes(auth.passphrase) else null,
                    )
                }
                is ConnectionConfig.AuthMethod.PrivateKeys -> {
                    // Pass each candidate's cert (when present) as the
                    // public-key arg so a CA-only server accepts a cert-backed
                    // key even when it isn't explicitly assigned to the
                    // profile. Without this the bare pubkey is offered and the
                    // server rejects it. (#185)
                    auth.keys.forEachIndexed { i, entry ->
                        try {
                            jsch.addIdentity(
                                "haven-key-$i-${entry.label}-${System.nanoTime()}",
                                entry.keyBytes,
                                entry.certificateBytes?.let { SshCertificateParser.toOpenSshPublicKeyLine(it) },
                                // #381: an encrypted key in the pool carries its
                                // stored passphrase so JSch can decrypt it at add
                                // time; a plaintext key has null and loads as-is.
                                entry.passphrase,
                            )
                        } catch (e: Exception) {
                            // #381: one bad key (e.g. an encrypted key whose stored
                            // passphrase is stale) must not abort the whole "try
                            // every key" pool — skip it and let the others be
                            // offered. A pinned single key still surfaces its error
                            // via the PrivateKey branch above.
                            diag("Skipped pool key '${entry.label}' — ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                }
                // SK (FIDO2) keys are collected and added together below, so a
                // profile listing several can accept whichever key the user
                // actually presents instead of insisting on the first (#237).
                is ConnectionConfig.AuthMethod.FidoKey -> { }
            }
        }
        applyFidoAuth(
            flat.filterIsInstance<ConnectionConfig.AuthMethod.FidoKey>(),
            sess,
            deferAnyKeyDetection = anyHardwareKeyDetectionShouldDefer(flat),
        )
        return fallbackKiPassword
    }

    /**
     * Add the profile's SK (FIDO2) keys as JSch identities, split by intent:
     *
     * - **Required** keys ([FidoKey.anyOf] == false — pinned, or several listed
     *   to require all): every one is offered unconditionally, so a server
     *   configured for a multi-key chain (`AuthenticationMethods
     *   publickey,publickey`) can complete its rounds (the user touches each).
     * - **Any-hardware-key** pool ([FidoKey.anyOf] == true): with more than one,
     *   [FidoAuthenticator.detectPresentSkKey] asks the user to present any one
     *   and only that key is offered (either/or, #237) — so SSH's "first trusted
     *   key wins" rule doesn't force whichever key happens to be listed first.
     *   If nothing is detected (all verify-required, or none presented in time)
     *   it falls back to offering all of them in order.
     */
    private fun applyFidoAuth(
        fidoAuths: List<ConnectionConfig.AuthMethod.FidoKey>,
        sess: Session,
        deferAnyKeyDetection: Boolean = false,
    ) {
        if (fidoAuths.isEmpty()) return
        val (anyOfPool, required) = fidoAuths.partition { it.anyOf }
        // Required / pinned: offer all so a multi-key server is satisfiable.
        required.forEach { addFidoIdentity(it, sess) }
        when {
            anyOfPool.isEmpty() -> {}
            anyOfPool.size == 1 -> addFidoIdentity(anyOfPool[0], sess)
            else -> {
                if (deferAnyKeyDetection) {
                    // The pool sits behind a higher-priority software key in the
                    // ordered list: don't prompt the user to present a key up front
                    // (that jumped ahead of the primary software key, which usually
                    // succeeds on its own). Register all pool keys so JSch offers
                    // them only if the software key fails — first server-trusted
                    // one wins. Keeps detection when the pool is the primary method.
                    Log.d(TAG, "Any-hardware-key: deferred (software key precedes) — " +
                        "registering all ${anyOfPool.size} as fallback, no up-front prompt")
                    anyOfPool.forEach { addFidoIdentity(it, sess) }
                    return
                }
                val candidates = anyOfPool.map { SkKeyData.deserialize(it.skKeyData) }
                val detected = try {
                    runBlocking { fidoAuthenticator?.detectPresentSkKey(candidates, keyLabel = null) }
                } catch (e: sh.haven.core.fido.FidoCancelledException) {
                    throw e // user cancelled the key prompt — abort the connect, don't fall back
                } catch (e: Exception) {
                    Log.w(TAG, "Any-hardware-key detection failed: ${e.message}")
                    null
                }
                val chosen = detected?.let { d ->
                    anyOfPool.firstOrNull {
                        SkKeyData.deserialize(it.skKeyData).credentialId.contentEquals(d.credentialId)
                    }
                }
                if (chosen != null) {
                    Log.d(TAG, "Any-hardware-key: offering the presented SK key only")
                    addFidoIdentity(chosen, sess)
                } else {
                    Log.d(TAG, "Any-hardware-key: none detected — offering all ${anyOfPool.size} in order")
                    anyOfPool.forEach { addFidoIdentity(it, sess) }
                }
            }
        }
    }

    private fun addFidoIdentity(auth: ConnectionConfig.AuthMethod.FidoKey, sess: Session) {
        val skData = SkKeyData.deserialize(auth.skKeyData)
        Log.d(TAG, "FIDO2 SK key: alg=${skData.algorithmName}")
        val authenticator = fidoAuthenticator ?: throw IllegalStateException(
            "FIDO security-key auth has no FidoAuthenticator configured on this " +
                "SshClient (key '${auth.keyLabel}') — set fidoAuthenticator before connect()."
        )
        val fidoIdentity = FidoIdentity(skData, authenticator, auth.keyLabel)
        val identity = if (auth.certBytes != null) {
            val certKeyType = SshCertificateParser.getCertKeyType(skData.algorithmName)
            CertificateWrappedIdentity(fidoIdentity, auth.certBytes, certKeyType)
        } else fidoIdentity
        jsch.addIdentity(identity, null)
        val currentAlgs = sess.getConfig("PubkeyAcceptedAlgorithms") ?: ""
        val skAlgs = "sk-ssh-ed25519@openssh.com,sk-ecdsa-sha2-nistp256@openssh.com"
        val skCertAlgs = "sk-ssh-ed25519-cert-v01@openssh.com,sk-ecdsa-sha2-nistp256-cert-v01@openssh.com"
        val advertised = if (auth.certBytes != null) "$skCertAlgs,$skAlgs" else skAlgs
        sess.setConfig(
            "PubkeyAcceptedAlgorithms",
            if (currentAlgs.isNotEmpty()) "$advertised,$currentAlgs" else advertised,
        )
    }

    /**
     * Connect to an SSH server using the given config.
     * This suspends on Dispatchers.IO.
     * Returns the host key as a [KnownHostEntry] for TOFU verification.
     */
    override suspend fun connect(
        config: ConnectionConfig,
        connectTimeoutMs: Int,
        proxy: HavenProxy?,
        keyboardInteractivePrompter: KeyboardInteractivePrompter?,
        totpCodeProvider: (() -> String)?,
        confirmOtp: Boolean,
        preConnect: (suspend () -> Unit)?,
        trustedHostCaKeys: List<String>,
    ): KnownHostEntry? = withContext(Dispatchers.IO) {
        disconnect()
        hostVerifiedByCa = false
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host, family = config.addressFamily)
        connectedViaProxy = proxy != null
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy.jschProxy)
        // Accept any key at the JSch level; we verify post-connect ourselves (TOFU)
        sess.setConfig("StrictHostKeyChecking", "no")
        // #133: trusted host-CA keys activate JSch's native OpenSSH host-
        // certificate verification. A CA-signed host cert that validates makes
        // TOFU unnecessary (connect returns null); anything else falls back to
        // the stripped key and the usual TOFU flow.
        val caRepo = installHostCaRepository(sess, trustedHostCaKeys)
        // Disable GSSAPI auth — it causes multi-second timeouts on most servers
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        val fallbackKiPassword: CharArray? = applyAuthMethods(config, sess)

        if (keyboardInteractivePrompter != null) {
            sess.userInfo = KeyboardInteractiveUserInfo(
                destination = "${config.username}@${config.host}:${config.port}",
                prompter = keyboardInteractivePrompter,
                fallbackPassword = fallbackKiPassword,
                totpCodeProvider = totpCodeProvider,
                autoSubmit = !confirmOtp,
            )
        }

        // Apply user SSH options (overrides defaults above). The applier
        // translates OpenSSH directive names (KexAlgorithms, Ciphers, …)
        // to JSch's internal keys and handles +/-/^ list prefixes — see #155.
        SshOptionsApplier.apply(sess, config.sshOptions)

        // Port-knock hook (when configured): runs after socket params are set
        // but before JSch opens the TCP connection. Throwing here aborts the
        // connect cleanly without leaving a half-built session behind.
        preConnect?.invoke()

        try {
            sess.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            // A proxied connect that timed out waiting for the target's first
            // byte fails here as a generic "session is down" — the channel was
            // torn down under JSch to unblock it. Say what actually happened
            // instead, so the connection log names the hop that went quiet (#383).
            val jump = (proxy?.jschProxy as? ProxyJump)
            if (jump?.timedOut == true) {
                try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
                throw JSchException(jump.timeoutMessage(connectTimeoutMs), e)
            }
            // KEX may have completed before the auth step failed — e.g. encrypted
            // keys tried with null passphrase, MaxAuthTries tripped, or wrong
            // remembered password. In that case JSch already has the server's
            // host key, and the caller needs it to drive TOFU verification so
            // the user sees a fingerprint prompt on first contact instead of
            // just an opaque "Auth fail" error. See GlassOnTin/Haven#75 follow-up.
            val capturedHostKey = tryExtractHostKey(sess, config.host, config.port)
            try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
            if (capturedHostKey != null) throw HostKeyAuthFailure(capturedHostKey, e)
            throw e
        } finally {
            // Release any NFC field held open for the one-tap either/or sign so
            // it can't outlive this connect attempt (#237).
            fidoAuthenticator?.releaseHeldNfc()
        }
        session = sess
        registerAgentIdentities(config)
        extractHostKey(sess, config.host, config.port, caRepo)
    }

    /**
     * Open an interactive shell channel on the current SSH session.
     * Must be called after [connect].
     *
     * Returns the channel together with its streams: they are bound before the
     * channel is connected, because JSch drops anything the remote sends before
     * `getInputStream()` has installed its pipe. Read them from the returned
     * [ShellChannel] — never re-fetch `channel.inputStream` (#382).
     */
    override fun openShellChannel(
        term: String,
        cols: Int,
        rows: Int,
    ): ShellChannel {
        val sess = session ?: throw IllegalStateException("Not connected")
        val shell = openShellOn(sess, term, cols, rows, agentForwardingEnabled)
        if (agentForwardingEnabled) diag("Shell channel opened with agent forwarding enabled")
        return shell
    }

    /**
     * Resize the PTY of an open shell channel.
     */
    /**
     * Open an SFTP channel on the current SSH session.
     * Must be called after [connect].
     */
    fun openSftpChannel(): ChannelSftp {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("sftp") as ChannelSftp
        channel.connect()
        return channel
    }

    /**
     * Open a new [sh.haven.core.ssh.sftp.SftpSession] on the current SSH
     * session — Haven-internal facade over [openSftpChannel] so callers in
     * feature- and app-modules do not import JSch types directly. Must be
     * called after [connect].
     */
    override fun openSftpSession(): sh.haven.core.ssh.sftp.SftpSession =
        sh.haven.core.ssh.sftp.JschSftpSession(openSftpChannel())

    /**
     * Execute a command on the remote host and return stdout, stderr, and exit status.
     * Must be called after [connect].
     *
     * [timeoutMs], when set, bounds the whole exec: at the deadline the
     * channel is force-disconnected (the only way to unblock the stream
     * reads — they are plain blocking IO, not cancellation-responsive) and
     * the partial result is returned with [ExecResult.timedOut] = true.
     * Null (the default) preserves the historical block-until-exit behaviour.
     */
    override suspend fun execCommand(command: String, timeoutMs: Long?): ExecResult = withContext(Dispatchers.IO) {
        val sess = session ?: throw IllegalStateException("Not connected")
        val channel = sess.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        if (agentForwardingEnabled) {
            channel.setAgentForwarding(true)
            diag("Exec channel opened with agent forwarding enabled: ${command.take(64)}")
        }
        channel.inputStream = null

        val stdout = channel.inputStream
        val stderr = channel.errStream

        channel.connect()

        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        // Drain stdout and stderr concurrently. JSch delivers both channels on
        // one session thread into bounded (~32 KB) pipes, so reading stdout to
        // EOF *before* touching stderr deadlocks when a command emits more than
        // a pipe-buffer of stderr before stdout closes. (#208 finding 11)
        val (outBytes, errBytes) = coroutineScope {
            val watchdog = timeoutMs?.let {
                async {
                    kotlinx.coroutines.delay(it)
                    timedOut.set(true)
                    // Closing the channel closes both pipe streams, which is
                    // what actually unblocks the readers below.
                    runCatching { channel.disconnect() }
                }
            }
            try {
                val errDeferred = async {
                    try {
                        stderr.readBytes()
                    } catch (e: java.io.IOException) {
                        if (timedOut.get()) ByteArray(0) else throw e
                    }
                }
                val out = try {
                    stdout.readBytes()
                } catch (e: java.io.IOException) {
                    if (timedOut.get()) ByteArray(0) else throw e
                }
                out to errDeferred.await()
            } finally {
                watchdog?.cancel()
            }
        }

        // Wait for channel to close so exitStatus is available
        while (!channel.isClosed) {
            Thread.sleep(50)
        }

        val result = ExecResult(
            exitStatus = if (timedOut.get()) -1 else channel.exitStatus,
            stdout = outBytes.decodeToString(),
            stderr = errBytes.decodeToString(),
            timedOut = timedOut.get(),
        )
        channel.disconnect()
        result
    }

    /**
     * Connect synchronously (for use on background threads like reconnect).
     * Same as [connect] but without the coroutine wrapper.
     * Returns the host key as a [KnownHostEntry] for TOFU verification.
     */
    override fun connectBlocking(
        config: ConnectionConfig,
        connectTimeoutMs: Int,
        proxy: HavenProxy?,
        keyboardInteractivePrompter: KeyboardInteractivePrompter?,
        totpCodeProvider: (() -> String)?,
        confirmOtp: Boolean,
        preConnect: (() -> Unit)?,
        trustedHostCaKeys: List<String>,
    ): KnownHostEntry? {
        disconnect()
        hostVerifiedByCa = false
        verboseLogger?.let { jsch.setInstanceLogger(it) }

        val resolvedIp = if (proxy != null) config.host else resolveHost(config.host, family = config.addressFamily)
        connectedViaProxy = proxy != null
        val sess = jsch.getSession(config.username, resolvedIp, config.port)
        if (proxy != null) sess.setProxy(proxy.jschProxy)
        sess.setConfig("StrictHostKeyChecking", "no")
        val caRepo = installHostCaRepository(sess, trustedHostCaKeys)
        sess.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        sess.serverAliveInterval = 15_000
        sess.serverAliveCountMax = 3

        val fallbackKiPassword: CharArray? = applyAuthMethods(config, sess)

        if (keyboardInteractivePrompter != null) {
            sess.userInfo = KeyboardInteractiveUserInfo(
                destination = "${config.username}@${config.host}:${config.port}",
                prompter = keyboardInteractivePrompter,
                fallbackPassword = fallbackKiPassword,
                totpCodeProvider = totpCodeProvider,
                autoSubmit = !confirmOtp,
            )
        }

        SshOptionsApplier.apply(sess, config.sshOptions)

        // See [connect] for the rationale on hook placement.
        preConnect?.invoke()

        try {
            sess.connect(connectTimeoutMs)
        } catch (e: JSchException) {
            // Mirror of the async connect() path — see the comments there.
            val jump = (proxy?.jschProxy as? ProxyJump)
            if (jump?.timedOut == true) {
                try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
                throw JSchException(jump.timeoutMessage(connectTimeoutMs), e)
            }
            val capturedHostKey = tryExtractHostKey(sess, config.host, config.port)
            try { sess.disconnect() } catch (_: Throwable) { /* best effort */ }
            if (capturedHostKey != null) throw HostKeyAuthFailure(capturedHostKey, e)
            throw e
        } finally {
            fidoAuthenticator?.releaseHeldNfc()
        }
        session = sess
        registerAgentIdentities(config)
        return extractHostKey(sess, config.host, config.port, caRepo)
    }

    /**
     * Log a line to Android logcat *and* the JSch verbose logger so the line
     * is captured in the per-connection verbose log that ships with the
     * Connection Log entry when users enable "Verbose SSH logging". This is
     * how we make agent-forwarding diagnostics visible to end users who want
     * to share logs via the connection log viewer.
     */
    private fun diag(message: String) {
        Log.d(TAG, message)
        verboseLogger?.log(com.jcraft.jsch.Logger.INFO, "[haven/agent] $message")
    }

    /**
     * Enable agent forwarding for this session and add the configured identities to the
     * JSch-wide identity repository so JSch's ChannelAgentForwarding can answer forwarded
     * SSH_AGENTC_REQUEST_IDENTITIES / SIGN_REQUEST messages from the remote.
     *
     * Must be called AFTER [Session.connect] so the identities are never tried as
     * candidate keys during public-key auth — otherwise a profile with many stored keys
     * could trip `MaxAuthTries` and be rejected with "Too many authentication failures".
     *
     * Emits diagnostic lines via [diag] so enabling Verbose SSH logging gives
     * users enough detail to file meaningful bug reports about forwarded-agent
     * behaviour (see #75 thread).
     */
    private fun registerAgentIdentities(config: ConnectionConfig) {
        agentForwardingEnabled = config.forwardAgent
        if (!config.forwardAgent) {
            diag("forwardAgent=false — not registering any agent identities")
            return
        }
        // The primary-auth keys (added via jsch.addIdentity during the auth step)
        // are still in JSch's identity repo, and ChannelAgentForwarding exposes
        // the ENTIRE repo over the forwarded socket — so a malicious remote could
        // request signatures from the user's auth keys even when no agent
        // identities were configured. Auth is complete by now, so clear the repo
        // and re-add only the explicitly-forwarded identities. (#208 finding 3)
        try {
            jsch.identityRepository.removeAll()
        } catch (e: Throwable) {
            diag("Could not clear identity repo before agent registration: ${e.message}")
        }
        if (config.agentIdentities.isEmpty()) {
            diag(
                "forwardAgent=true but agentIdentities is empty — the forwarded " +
                    "agent channel will expose no keys (repo cleared). Typical cause: " +
                    "all stored SSH keys are passphrase-protected with no stored " +
                    "passphrase, so the caller (ConnectionsViewModel.agentIdentitiesFor) " +
                    "filtered them out. Store each key's passphrase to forward it."
            )
            logJschIdentityRepo()
            return
        }
        var registered = 0
        var skipped = 0
        config.agentIdentities.forEachIndexed { i, identity ->
            try {
                // Passing the passphrase makes JSch decrypt the key AT ADD
                // TIME — required for forwarding, because
                // ChannelAgentForwarding silently skips identities still
                // reporting isEncrypted(). (#377)
                jsch.addIdentity("haven-agent-$i-${identity.label}", identity.keyBytes, null, identity.passphrase)
                registered++
                diag(
                    "Registered agent identity #$i '${identity.label}' " +
                        "(${identity.keyBytes.size} bytes" +
                        (if (identity.passphrase != null) ", decrypted at add" else "") + ")",
                )
            } catch (e: Exception) {
                skipped++
                diag("Skipped agent identity #$i '${identity.label}' — ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        diag("Agent identities: $registered registered, $skipped skipped, ${config.agentIdentities.size} requested")
        logJschIdentityRepo()
    }

    /**
     * Dump the names of every identity currently in JSch's repository.
     * ChannelAgentForwarding answers SSH_AGENTC_REQUEST_IDENTITIES with the
     * full repo contents, so this is what the remote will actually see over
     * the forwarded agent socket. Useful for diagnosing cases where
     * `ssh-add -l` on the remote returns something different from what the
     * user configured under the "Forward SSH agent" toggle.
     */
    private fun logJschIdentityRepo() {
        try {
            val names = jsch.identityNames
            if (names.isEmpty()) {
                diag("JSch identity repo: EMPTY — forwarded agent will report no keys")
            } else {
                diag("JSch identity repo (${names.size} entries, will be exposed over forwarded agent):")
                for ((i, name) in names.withIndex()) {
                    diag("  [$i] $name")
                }
            }
        } catch (e: Throwable) {
            diag("Could not read JSch identity repo: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Encode a `CharArray` to its UTF-8 byte representation without
     * passing through `String`. JSch's `setPassword(byte[])` and
     * `addIdentity(..., byte[] passphrase)` overloads are used so the
     * secret never lands in an immutable `String` (which can't be
     * zeroed). The returned `ByteArray` is handed to JSch, which
     * keeps a reference for the lifetime of the auth attempt — the
     * caller is responsible for clearing the source `CharArray` via
     * `AuthMethod.Password.clear()` / `AuthMethod.PrivateKey.clear()`
     * once the session is established.
     */
    private fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val cb = java.nio.CharBuffer.wrap(chars)
        val bb = Charsets.UTF_8.encode(cb)
        val limit = bb.limit()
        val out = ByteArray(limit)
        bb.get(out)
        // Best-effort wipe of the temporary ByteBuffer's backing array
        // before it goes out of scope. No-op for a direct buffer.
        if (bb.hasArray()) {
            val backing = bb.array()
            val from = bb.arrayOffset()
            java.util.Arrays.fill(backing, from, from + limit, 0.toByte())
        }
        return out
    }

    /**
     * Install a [TrustedCaHostKeyRepository] on the session when the user has
     * trusted host-CA keys configured (#133). Returns the repository so the
     * post-connect [extractHostKey] can read its state, or null when no CAs
     * are configured (today's behaviour, byte for byte).
     */
    private fun installHostCaRepository(
        sess: Session,
        trustedHostCaKeys: List<String>,
    ): TrustedCaHostKeyRepository? {
        if (trustedHostCaKeys.isEmpty()) return null
        val repo = TrustedCaHostKeyRepository(trustedHostCaKeys)
        if (repo.caCount == 0) return null
        sess.setHostKeyRepository(repo)
        diag("host-CA verification active: ${repo.caCount} trusted CA(s)")
        return repo
    }

    /**
     * The host key for TOFU verification, or null when the host was verified
     * by a trusted CA-signed host certificate and TOFU is unnecessary.
     *
     * The null reading is deliberately narrow: the only JSch code path that
     * completes a connect without setting the session's host key is
     * Session.checkHost returning early after
     * OpenSshCertificateHostKeyVerifier validated the presented certificate
     * (signature, validity window, principals, revocation) against an
     * `@cert-authority` entry served by [TrustedCaHostKeyRepository]. That
     * reading is only trusted when our repository was actually installed and
     * its TOFU check() went unconsulted; any other missing-host-key state is
     * unexpected and fails closed.
     */
    private fun extractHostKey(
        sess: Session,
        host: String,
        port: Int,
        caRepo: TrustedCaHostKeyRepository? = null,
    ): KnownHostEntry? {
        val hk = sess.hostKey
        if (hk == null) {
            if (caRepo != null && caRepo.caCount > 0 && !caRepo.checkConsulted) {
                diag("host $host:$port verified by trusted host CA — skipping TOFU")
                hostVerifiedByCa = true
                return null
            }
            throw JSchException(
                "No host key available after connect to $host:$port (not CA-verified) — failing closed")
        }
        return KnownHostEntry(
            hostname = host,
            port = port,
            keyType = hk.type,
            // JSch HostKey.getKey() returns the base64-encoded public key
            publicKeyBase64 = hk.key,
        )
    }

    /**
     * Nullable variant used when we can't be sure KEX completed — e.g. called
     * from the catch block after a failed [Session.connect]. JSch exposes
     * [Session.getHostKey] only after the KEX init response arrives, so a
     * null return here means the failure happened earlier in the handshake
     * (connect refused, bad version, KEX alg mismatch, etc.) and there is no
     * host key for the caller to verify.
     */
    private fun tryExtractHostKey(sess: Session, host: String, port: Int): KnownHostEntry? {
        return try {
            val hk = sess.hostKey ?: return null
            KnownHostEntry(
                hostname = host,
                port = port,
                keyType = hk.type,
                publicKeyBase64 = hk.key,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Set up local port forwarding (ssh -L).
     * Returns the actual bound port (useful if bindPort is 0 for ephemeral).
     */
    override fun setPortForwardingL(bindAddress: String, localPort: Int, remoteHost: String, remotePort: Int): Int {
        val sess = session ?: throw IllegalStateException("Not connected")
        return sess.setPortForwardingL(bindAddress, localPort, remoteHost, remotePort)
    }

    /**
     * Set up remote port forwarding (ssh -R).
     */
    override fun setPortForwardingR(bindAddress: String, remotePort: Int, localHost: String, localPort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.setPortForwardingR(bindAddress, remotePort, localHost, localPort)
    }

    /**
     * Remove a local port forward.
     */
    override fun delPortForwardingL(bindAddress: String, localPort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.delPortForwardingL(bindAddress, localPort)
    }

    /**
     * Remove a remote port forward.
     */
    override fun delPortForwardingR(remotePort: Int) {
        val sess = session ?: throw IllegalStateException("Not connected")
        sess.delPortForwardingR(remotePort)
    }

    // --- Dynamic (SOCKS5) port forwarding (ssh -D) ---

    /** Active dynamic forwards keyed by (bindAddress, bindPort). */
    private val dynamicForwards = mutableMapOf<Pair<String, Int>, DynamicForwardServer>()

    /**
     * Start a SOCKS5 proxy server on the given local address/port. Each
     * accepted connection is tunneled through an SSH `direct-tcpip` channel.
     * Returns the port actually bound (useful if bindPort is 0).
     */
    override fun setPortForwardingDynamic(bindAddress: String, bindPort: Int): Int {
        val sess = session ?: throw IllegalStateException("Not connected")
        val server = DynamicForwardServer(sess, bindAddress, bindPort)
        val actualPort = server.start()
        synchronized(dynamicForwards) {
            // Key by the originally requested port so removal is deterministic;
            // store under both keys if 0 was requested
            dynamicForwards[bindAddress to actualPort] = server
            if (bindPort == 0) {
                dynamicForwards[bindAddress to 0] = server
            }
        }
        return actualPort
    }

    /** Stop a dynamic forward previously started with [setPortForwardingDynamic]. */
    override fun delPortForwardingDynamic(bindAddress: String, bindPort: Int) {
        synchronized(dynamicForwards) {
            val server = dynamicForwards.remove(bindAddress to bindPort)
            if (server != null) {
                // Also remove any alias entry
                dynamicForwards.entries.removeAll { it.value === server }
                try { server.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Disconnect the current session and clear loaded identities.
     */
    override fun disconnect() {
        // Close any dynamic forward servers before tearing down the session
        synchronized(dynamicForwards) {
            dynamicForwards.values.toSet().forEach {
                try { it.close() } catch (_: Exception) {}
            }
            dynamicForwards.clear()
        }
        session?.disconnect()
        session = null
        agentForwardingEnabled = false
        jsch.removeAllIdentity()
    }

    override fun close() = disconnect()

    companion object {
        /** No-op, kept for API compatibility. DNS is resolved fresh on each connection. */
        fun clearDnsCache() { }

        /**
         * Fetch a server's host key by running the SSH handshake through KEX
         * and abandoning at auth — an in-process ssh-keyscan (#376 host
         * rediscovery). "none" auth keeps the exchange short; JSch exposes the
         * key once KEX completes, so the expected auth failure still yields
         * it. Null when the host is unreachable or the handshake died before
         * KEX (refused, not an SSH server, algorithm mismatch).
         */
        fun keyScan(host: String, port: Int, timeoutMs: Int = 4_000): KnownHostEntry? = try {
            val sess = JSch().getSession("haven-keyscan", host, port)
            sess.setConfig("StrictHostKeyChecking", "no")
            sess.setConfig("PreferredAuthentications", "none")
            try {
                sess.connect(timeoutMs)
            } catch (_: JSchException) {
                // Expected: auth fails after KEX; the host key is already set.
            }
            val hk = try { sess.hostKey } catch (_: Throwable) { null }
            runCatching { sess.disconnect() }
            hk?.let {
                KnownHostEntry(hostname = host, port = port, keyType = it.type, publicKeyBase64 = it.key)
            }
        } catch (_: Exception) {
            null
        }

        /**
         * Whether the "Any hardware key" up-front detection prompt
         * ([FidoAuthenticator.detectPresentSkKey], used for a pool of >1
         * enrolled hardware keys) should be SKIPPED because a higher-priority
         * software key precedes the pool in the ordered auth list [flat].
         *
         * Without this the "present your key" prompt fired during connect
         * *setup* even when the hardware key was a secondary fallback behind a
         * software key that would have authenticated on its own — so the
         * hardware key appeared to be "chosen ahead of" the primary. When
         * deferred, the pool keys are still registered as identities (offered
         * only if the software key fails, first server-trusted one winning).
         * Detection is kept when the pool IS the first publickey (no software
         * key before it), preserving the #237 either-of touch UX.
         *
         * A preceding [Password] doesn't count: publickey is always offered
         * before password, so the pool is still the first publickey tried.
         */
        internal fun anyHardwareKeyDetectionShouldDefer(
            flat: List<ConnectionConfig.AuthMethod>,
        ): Boolean {
            val firstFido = flat.indexOfFirst { it is ConnectionConfig.AuthMethod.FidoKey }
            if (firstFido < 0) return false
            return flat.take(firstFido).any {
                it is ConnectionConfig.AuthMethod.PrivateKey ||
                    it is ConnectionConfig.AuthMethod.PrivateKeys
            }
        }

        /**
         * Resolve a hostname to an IP address string.
         * For .local hostnames, tries a direct mDNS query first (fast, ~50-100ms)
         * before falling back to the system resolver.
         * Resolved fresh each time — no application-level caching — so network
         * changes (e.g. switching between local and remote DNS) take effect
         * without restarting the app.
         */
        fun resolveHost(
            hostname: String,
            family: ConnectionConfig.AddressFamily = ConnectionConfig.AddressFamily.AUTO,
        ): String {
            // IPv4 literal — skip resolution. With family=IPV6_ONLY this is a
            // user choice to override their own preference; pass it through
            // and let JSch surface any failure naturally.
            if (hostname.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return hostname

            // .onion addresses must not be resolved locally — they require a SOCKS proxy
            if (hostname.endsWith(".onion")) return hostname

            val ip = if (hostname.endsWith(".local") || hostname.endsWith(".local.")) {
                resolveMdns(hostname) ?: resolveSystem(hostname, family)
            } else {
                resolveSystem(hostname, family)
            }

            if (ip != null) return ip

            val why = when (family) {
                ConnectionConfig.AddressFamily.IPV4_ONLY ->
                    " (no A record / IPv4 address found, IPv4-only enabled)"
                ConnectionConfig.AddressFamily.IPV6_ONLY ->
                    " (no AAAA record / IPv6 address found, IPv6-only enabled)"
                ConnectionConfig.AddressFamily.AUTO -> ""
            }
            throw java.net.UnknownHostException("Could not resolve hostname: $hostname$why")
        }

        private fun resolveSystem(
            hostname: String,
            family: ConnectionConfig.AddressFamily = ConnectionConfig.AddressFamily.AUTO,
        ): String? {
            return try {
                // InetAddress.getByName/getAllByName has no timeout — run it in a thread with a deadline
                val future = java.util.concurrent.CompletableFuture.supplyAsync {
                    when (family) {
                        ConnectionConfig.AddressFamily.AUTO ->
                            InetAddress.getByName(hostname).hostAddress
                        ConnectionConfig.AddressFamily.IPV4_ONLY ->
                            InetAddress.getAllByName(hostname)
                                .firstOrNull { it is java.net.Inet4Address }
                                ?.hostAddress
                        ConnectionConfig.AddressFamily.IPV6_ONLY ->
                            InetAddress.getAllByName(hostname)
                                .firstOrNull { it is java.net.Inet6Address }
                                ?.hostAddress
                    }
                }
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.w(TAG, "DNS resolve timed out for $hostname")
                null
            } catch (e: Exception) {
                val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
                Log.w(TAG, "System DNS resolve failed for $hostname", cause)
                null
            }
        }

        /**
         * Direct mDNS query for .local hostnames.
         * Sends a unicast-response mDNS query to 224.0.0.251:5353 and parses
         * the A record from the response. Timeout 1.5s (vs ~4s system fallback).
         */
        private fun resolveMdns(hostname: String): String? {
            val name = hostname.removeSuffix(".")
            return try {
                val query = buildMdnsQuery(name)
                val socket = DatagramSocket()
                socket.soTimeout = 1500
                try {
                    val mdnsAddr = InetAddress.getByName("224.0.0.251")
                    socket.send(DatagramPacket(query, query.size, mdnsAddr, 5353))
                    val buf = ByteArray(512)
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    parseMdnsARecord(buf, resp.length)
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.d(TAG, "mDNS resolve failed for $hostname: ${e.message}")
                null
            }
        }

        /**
         * Build a minimal DNS query packet for an A record.
         * Transaction ID = 0, QR=0 (query), QDCOUNT=1, one question for [name] type A class IN.
         */
        private fun buildMdnsQuery(name: String): ByteArray {
            val buf = ByteBuffer.allocate(256)
            // Header: ID=0, flags=0, QDCOUNT=1
            buf.putShort(0) // ID
            buf.putShort(0) // Flags (standard query)
            buf.putShort(1) // QDCOUNT
            buf.putShort(0) // ANCOUNT
            buf.putShort(0) // NSCOUNT
            buf.putShort(0) // ARCOUNT
            // Question: name labels
            for (label in name.split('.')) {
                buf.put(label.length.toByte())
                buf.put(label.toByteArray(Charsets.US_ASCII))
            }
            buf.put(0.toByte()) // terminator
            buf.putShort(1) // QTYPE = A
            buf.putShort(1) // QCLASS = IN
            return buf.array().copyOf(buf.position())
        }

        /**
         * Parse an mDNS response and extract the first A record (IPv4 address).
         */
        private fun parseMdnsARecord(data: ByteArray, length: Int): String? {
            if (length < 12) return null
            val buf = ByteBuffer.wrap(data, 0, length)
            buf.position(2) // skip ID
            buf.short // flags
            val qdCount = buf.short.toInt() and 0xFFFF
            val anCount = buf.short.toInt() and 0xFFFF
            buf.short // nscount
            buf.short // arcount

            // Skip questions
            repeat(qdCount) {
                skipDnsName(buf)
                if (buf.remaining() < 4) return null
                buf.short // qtype
                buf.short // qclass
            }

            // Parse answers
            repeat(anCount) {
                skipDnsName(buf)
                if (buf.remaining() < 10) return null
                val type = buf.short.toInt() and 0xFFFF
                buf.short // class
                buf.int   // TTL
                val rdLength = buf.short.toInt() and 0xFFFF
                if (type == 1 && rdLength == 4 && buf.remaining() >= 4) {
                    // A record — 4 bytes IPv4
                    val a = buf.get().toInt() and 0xFF
                    val b = buf.get().toInt() and 0xFF
                    val c = buf.get().toInt() and 0xFF
                    val d = buf.get().toInt() and 0xFF
                    return "$a.$b.$c.$d"
                }
                if (buf.remaining() >= rdLength) {
                    buf.position(buf.position() + rdLength)
                } else return null
            }
            return null
        }

        private fun skipDnsName(buf: ByteBuffer) {
            while (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) {
                    // Compression pointer — one more byte
                    if (buf.hasRemaining()) buf.get()
                    break
                }
                if (buf.remaining() >= len) {
                    buf.position(buf.position() + len)
                } else break
            }
        }
    }
}
