package sh.haven.core.mail

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * [MailClient] over generic IMAP using the android-mail (JavaMail) build. The
 * read path mirrors [ProtonMailClient]: list folders, list envelopes, and fetch
 * a message as raw RFC822 bytes (`Message.writeTo`) so the feature layer parses
 * it with the same `MimeParser` both engines share. [send] (CP-6) builds a
 * `MimeMessage` and posts it over SMTP on the same tunneled [SocketFactory],
 * then best-effort files a copy in the account's Sent folder.
 *
 * The JVM [Store] for each [sessionId] is held here; the transport rides the
 * per-profile tunnel via [MailConnectParams.Imap.socketFactory] (a plain
 * tunneled socket), with TLS layered on top by [TunnelingSSLSocketFactory] when
 * the account uses implicit SSL.
 */
@Singleton
class ImapMailClient @Inject constructor() : MailClient {

    private class ImapSession(
        val store: Store,
        val session: Session,
        val params: MailConnectParams.Imap,
    )

    private val sessions = ConcurrentHashMap<String, ImapSession>()

    override suspend fun login(
        sessionId: String,
        params: MailConnectParams,
    ): MailLoginResult = withContext(Dispatchers.IO) {
        val p = params as? MailConnectParams.Imap
            ?: throw IllegalArgumentException(
                "ImapMailClient requires MailConnectParams.Imap, got ${params::class.simpleName}",
            )
        val session = Session.getInstance(buildProps(p))
        val store = session.getStore(if (p.tls) "imaps" else "imap")
        try {
            store.connect(p.server, p.port, p.username, p.password)
        } catch (e: AuthenticationFailedException) {
            throw MailException.AuthFailed(e.message ?: "IMAP authentication failed")
        } catch (e: MessagingException) {
            throw MailException.ProtocolError(0, e.message ?: "IMAP connection failed")
        }
        sessions[sessionId] = ImapSession(store, session, p)
        // IMAP has no token/keyring; the result fields are Proton-shaped and unused here.
        MailLoginResult(uid = p.username, accessToken = "", refreshToken = "", saltedKeyPass = "")
    }

    override suspend fun listFolders(sessionId: String): List<MailFolder> =
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val listed = runCatching { s.store.defaultFolder.list("*").toList() }.getOrDefault(emptyList())
            val folders = listed.map { f ->
                MailFolder(id = f.fullName, name = f.name.ifBlank { f.fullName }, type = 0)
            }
            // Some servers don't return INBOX from list("*"); ensure it's present and first.
            if (folders.none { it.isInbox }) {
                listOf(MailFolder(id = "INBOX", name = "INBOX", type = 0)) + folders
            } else {
                folders.sortedByDescending { it.isInbox }
            }
        }

    override suspend fun listMessages(
        sessionId: String,
        folderId: String,
        desc: Boolean,
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val s = session(sessionId)
        val folder = s.store.getFolder(folderId)
        folder.open(Folder.READ_ONLY)
        try {
            val msgs = folder.messages
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            }
            folder.fetch(msgs, fp)
            val uf = folder as UIDFolder
            val list = msgs.map { m -> toMailMessage(m, uf, folderId) }
            // IMAP returns oldest-first; newest-first when desc.
            if (desc) list.asReversed() else list
        } catch (e: MessagingException) {
            throw MailException.ProtocolError(0, e.message ?: "IMAP list failed")
        } finally {
            runCatching { folder.close(false) }
        }
    }

    override suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val (folderId, uid) = decodeId(messageId)
            val folder = s.store.getFolder(folderId)
            folder.open(Folder.READ_ONLY)
            try {
                val m = (folder as UIDFolder).getMessageByUID(uid)
                    ?: throw MailException.ProtocolError(404, "Message $uid not found in $folderId")
                ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP fetch failed")
            } finally {
                runCatching { folder.close(false) }
            }
        }

    override suspend fun send(sessionId: String, mail: OutgoingMail): SendResult =
        withContext(Dispatchers.IO) {
            require(mail.to.isNotEmpty()) { "OutgoingMail.to must not be empty" }
            val s = session(sessionId)
            val p = s.params
            // A dedicated Session for SMTP: the stored read Session only carries
            // the IMAP socketFactory keys. The build/send is split into testable
            // helpers (buildSmtpProps / buildMimeMessage).
            val smtpSession = Session.getInstance(buildSmtpProps(p))
            val msg = buildMimeMessage(smtpSession, p, mail)
            try {
                val transport = smtpSession.getTransport(if (p.tls) "smtps" else "smtp")
                try {
                    // Pass the creds — JavaMail authenticates only when the server
                    // advertises AUTH (so a no-auth relay/test sink still works);
                    // mail.smtp.auth is intentionally NOT forced on.
                    transport.connect(p.server, p.smtpPort, p.username, p.password)
                    transport.sendMessage(msg, msg.allRecipients)
                } finally {
                    runCatching { transport.close() }
                }
            } catch (e: AuthenticationFailedException) {
                throw MailException.AuthFailed(e.message ?: "SMTP authentication failed")
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "SMTP send failed")
            }
            SendResult(
                messageId = runCatching { msg.messageID }.getOrNull(),
                appendedToSent = appendToSent(s, msg),
            )
        }

    override suspend fun logout(sessionId: String) {
        withContext(Dispatchers.IO) {
            sessions.remove(sessionId)?.let { runCatching { it.store.close() } }
        }
    }

    // ---- helpers ----

    private fun session(sessionId: String): ImapSession =
        sessions[sessionId] ?: throw MailException.SessionExpired("IMAP session $sessionId not found")

    private fun toMailMessage(m: Message, uf: UIDFolder, folderId: String): MailMessage {
        val from = (m.from?.firstOrNull() as? InternetAddress)
            ?.let { MailAddress(name = it.personal ?: "", address = it.address ?: "") }
        val to = (m.getRecipients(Message.RecipientType.TO) ?: emptyArray())
            .filterIsInstance<InternetAddress>()
            .map { MailAddress(name = it.personal ?: "", address = it.address ?: "") }
        val unread = runCatching { !m.flags.contains(Flags.Flag.SEEN) }.getOrDefault(true)
        val timeSeconds = (runCatching { m.receivedDate }.getOrNull()
            ?: runCatching { m.sentDate }.getOrNull())?.time?.div(1000) ?: 0L
        return MailMessage(
            id = encodeId(folderId, uf.getUID(m)),
            subject = runCatching { m.subject }.getOrNull() ?: "",
            from = from,
            to = to,
            unread = unread,
            timeSeconds = timeSeconds,
            numAttachments = 0, // not fetched in the list view (needs body structure)
        )
    }

    private fun buildProps(p: MailConnectParams.Imap): Properties = Properties().apply {
        // Register providers explicitly — Android strips META-INF/javamail.* so
        // protocol auto-discovery fails on-device; without this getStore() throws.
        this["mail.imap.class"] = "com.sun.mail.imap.IMAPStore"
        this["mail.imaps.class"] = "com.sun.mail.imap.IMAPSSLStore"
        this["mail.smtp.class"] = "com.sun.mail.smtp.SMTPTransport"
        this["mail.smtps.class"] = "com.sun.mail.smtp.SMTPSSLTransport"
        // Read timeout only. connectiontimeout is set per-branch: with a tunnel
        // factory it must NOT be set, because it makes JavaMail use the no-arg
        // SocketFactory.createSocket() + connect() path, which the tunnel factory
        // doesn't implement ("Unconnected sockets not implemented"). The tunnel's
        // own dial timeout bounds the connect instead.
        this["mail.imap.timeout"] = TIMEOUT_MS
        this["mail.imaps.timeout"] = TIMEOUT_MS

        val sf = p.socketFactory
        if (p.tls) {
            this["mail.store.protocol"] = "imaps"
            if (sf != null) {
                // Route the BASE socket through the tunnel. JavaMail's
                // SocketFetcher creates the base socket with `mail.imaps
                // .socketFactory`, then — seeing it isn't already an SSLSocket —
                // wraps it in implicit TLS itself. (`.ssl.socketFactory` is ONLY
                // the wrap factory; setting just that left the BASE socket on the
                // default direct factory → a clearnet leak past the tunnel.)
                // fallback=false => a dead/blocked tunnel fails the connect.
                this["mail.imaps.socketFactory"] = sf
                this["mail.imaps.socketFactory.fallback"] = "false"
                this["mail.imaps.ssl.checkserveridentity"] = "true"
            } else {
                this["mail.imaps.connectiontimeout"] = TIMEOUT_MS
            }
        } else {
            this["mail.store.protocol"] = "imap"
            if (sf != null) {
                this["mail.imap.socketFactory"] = sf
                this["mail.imap.socketFactory.fallback"] = "false"
            } else {
                this["mail.imap.connectiontimeout"] = TIMEOUT_MS
            }
        }
    }

    /**
     * SMTP session properties. Mirrors the IMAP base-socket lesson from CP-5:
     * route the BASE socket through the tunnel ([MailConnectParams.Imap.socketFactory])
     * with `fallback=false`, so a dead/blocked tunnel fails the connect instead of
     * silently leaking onto the clearnet. `.ssl.socketFactory` is only the wrap
     * factory and would leave the base socket on the default direct factory.
     */
    internal fun buildSmtpProps(p: MailConnectParams.Imap): Properties = Properties().apply {
        // Register providers explicitly — Android strips META-INF/javamail.* so
        // auto-discovery fails on-device; without this getTransport() throws.
        this["mail.smtp.class"] = "com.sun.mail.smtp.SMTPTransport"
        this["mail.smtps.class"] = "com.sun.mail.smtp.SMTPSSLTransport"
        this["mail.smtp.timeout"] = TIMEOUT_MS
        this["mail.smtps.timeout"] = TIMEOUT_MS

        val sf = p.socketFactory
        if (p.tls) {
            // Implicit TLS (typically 465). JavaMail's SocketFetcher creates the
            // base socket with `mail.smtps.socketFactory`, then wraps it in TLS.
            this["mail.transport.protocol"] = "smtps"
            if (sf != null) {
                this["mail.smtps.socketFactory"] = sf
                this["mail.smtps.socketFactory.fallback"] = "false"
                this["mail.smtps.ssl.checkserveridentity"] = "true"
            } else {
                this["mail.smtps.connectiontimeout"] = TIMEOUT_MS
            }
        } else {
            // Plaintext / STARTTLS (typically 587). Opportunistically upgrade to
            // TLS when the server offers STARTTLS (protects creds on a TLS-capable
            // server); not *required*, so a plaintext relay/test sink still works.
            this["mail.transport.protocol"] = "smtp"
            this["mail.smtp.starttls.enable"] = "true"
            if (sf != null) {
                this["mail.smtp.socketFactory"] = sf
                this["mail.smtp.socketFactory.fallback"] = "false"
            } else {
                this["mail.smtp.connectiontimeout"] = TIMEOUT_MS
            }
        }
    }

    /** Build the outgoing [MimeMessage]; `saveChanges()` finalises headers + assigns a Message-ID. */
    internal fun buildMimeMessage(
        smtpSession: Session,
        p: MailConnectParams.Imap,
        mail: OutgoingMail,
    ): MimeMessage = MimeMessage(smtpSession).apply {
        setFrom(fromAddress(p))
        setRecipients(Message.RecipientType.TO, toAddresses(mail.to))
        if (mail.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, toAddresses(mail.cc))
        if (mail.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, toAddresses(mail.bcc))
        setSubject(mail.subject, "UTF-8")
        setText(mail.bodyText, "UTF-8")
        sentDate = Date()
        saveChanges()
    }

    /** From the authenticated account; synthesise a domain when the username is a bare local part. */
    private fun fromAddress(p: MailConnectParams.Imap): InternetAddress =
        InternetAddress(if (p.username.contains('@')) p.username else "${p.username}@${p.server}")

    private fun toAddresses(addrs: List<String>): Array<javax.mail.Address> =
        addrs.mapNotNull { a -> a.trim().ifBlank { null }?.let { InternetAddress(it, false) } }
            .toTypedArray()

    /**
     * Best-effort file a copy of [msg] in the account's Sent folder over the live
     * IMAP [Store]. Never throws — a failed append must not fail the send.
     */
    private fun appendToSent(s: ImapSession, msg: MimeMessage): Boolean = runCatching {
        val sent = findSentFolder(s.store)?.takeIf { it.exists() } ?: return false
        sent.open(Folder.READ_WRITE)
        try {
            msg.setFlag(Flags.Flag.SEEN, true)
            sent.appendMessages(arrayOf<Message>(msg))
            true
        } finally {
            runCatching { sent.close(false) }
        }
    }.getOrElse {
        Log.w(TAG, "append-to-Sent failed: ${it.message}")
        false
    }

    /** Locate the Sent folder: prefer the RFC 6154 `\Sent` special-use attribute, then a name match. */
    private fun findSentFolder(store: Store): Folder? {
        val all = runCatching { store.defaultFolder.list("*").toList() }.getOrDefault(emptyList())
        all.firstOrNull { f ->
            runCatching {
                (f as? com.sun.mail.imap.IMAPFolder)?.attributes
                    ?.any { it.equals("\\Sent", ignoreCase = true) } == true
            }.getOrDefault(false)
        }?.let { return it }
        val names = setOf("sent", "sent items", "sent mail", "sent messages")
        return all.firstOrNull {
            it.name.lowercase() in names || it.fullName.lowercase() in names
        }
    }

    companion object {
        private const val TAG = "ImapMailClient"
        private const val TIMEOUT_MS = "30000"

        /**
         * Encode a stable opaque message id as `"<folderFullName> <uid>"`. The
         * UID is IMAP's per-folder stable identifier; the folder name is needed
         * because [getMessageRaw] reopens the folder to fetch by UID. [decodeId]
         * splits on the LAST space, so folder names containing spaces ("Sent
         * Items 42") round-trip; the numeric UID never contains one. A printable
         * space (not a control byte) keeps the id valid as a JSON string on the
         * MCP read path.
         */
        internal fun encodeId(folderId: String, uid: Long): String = "$folderId $uid"

        internal fun decodeId(messageId: String): Pair<String, Long> {
            val i = messageId.lastIndexOf(' ')
            require(i > 0 && i < messageId.length - 1) { "Malformed IMAP message id: $messageId" }
            val uid = messageId.substring(i + 1).toLongOrNull()
                ?: throw IllegalArgumentException("Malformed IMAP message id (uid): $messageId")
            return messageId.substring(0, i) to uid
        }
    }
}
