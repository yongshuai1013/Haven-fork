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
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

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
            val folders = listed.mapNotNull { f ->
                // attributes are cached from the LIST response (no extra round-trip).
                val attrs = runCatching { (f as? com.sun.mail.imap.IMAPFolder)?.attributes?.toList() }
                    .getOrNull().orEmpty()
                // Drop \Noselect containers (e.g. Gmail's "[Gmail]" parent): opening one
                // throws. 1b will reintroduce them as non-clickable section headers once the
                // folder list is hierarchical.
                if (!folderSelectable(attrs)) return@mapNotNull null
                MailFolder(
                    id = f.fullName,
                    name = f.name.ifBlank { f.fullName },
                    type = 0,
                    role = folderRole(f.name, attrs),
                )
            }
            // Some servers don't return INBOX from list("*"); ensure it's present.
            val withInbox = if (folders.none { it.isInbox }) {
                listOf(MailFolder(id = "INBOX", name = "INBOX", type = 0, role = MailFolderRole.INBOX)) + folders
            } else {
                folders
            }
            // Order by special-use role (Inbox · Starred · Important · Sent · Drafts ·
            // All Mail · Spam · Trash), then user folders/labels in the server's order.
            withInbox.withIndex()
                .sortedWith(compareBy({ it.value.role.sortOrder }, { it.index }))
                .map { it.value }
        }

    override suspend fun listMessages(
        sessionId: String,
        folderId: String,
        desc: Boolean,
        limit: Int,
        offset: Int,
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val s = session(sessionId)
        val fid = imapFolderId(folderId)
        val folder = s.store.getFolder(fid)
        folder.open(Folder.READ_ONLY)
        try {
            // Fetch only the most-recent [limit] envelopes (skipping [offset] from
            // the newest end) instead of the whole folder — a large inbox over the
            // tunnel was the dominant cost. IMAP message numbers are 1..count,
            // oldest..newest.
            val count = folder.messageCount
            val range = recentSlice(count, limit, offset)
                ?: return@withContext emptyList()
            val msgs = folder.getMessages(range.first, range.last)
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            }
            folder.fetch(msgs, fp)
            val uf = folder as UIDFolder
            val list = msgs.map { m -> toMailMessage(m, uf, fid) }
            // The slice is oldest-first within the window; newest-first when desc.
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

    override suspend fun setSeen(sessionId: String, messageId: String, seen: Boolean) =
        withMessageReadWrite(sessionId, messageId, expungeOnClose = false) { _, m ->
            m.setFlag(Flags.Flag.SEEN, seen)
        }

    override suspend fun setFlagged(sessionId: String, messageId: String, flagged: Boolean) =
        withMessageReadWrite(sessionId, messageId, expungeOnClose = false) { _, m ->
            m.setFlag(Flags.Flag.FLAGGED, flagged)
        }

    override suspend fun deleteMessage(sessionId: String, messageId: String) =
        // Mark \Deleted; close(true) expunges it. On Gmail this lands the copy in Trash.
        withMessageReadWrite(sessionId, messageId, expungeOnClose = true) { _, m ->
            m.setFlag(Flags.Flag.DELETED, true)
        }

    override suspend fun moveMessage(sessionId: String, messageId: String, destFolderId: String) {
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val (folderId, uid) = decodeId(messageId)
            val destFolderId = imapFolderId(destFolderId)
            val dest = s.store.getFolder(destFolderId)
            if (!dest.exists()) {
                throw MailException.ProtocolError(404, "Destination folder not found: $destFolderId")
            }
            val src = s.store.getFolder(folderId)
            src.open(Folder.READ_WRITE)
            try {
                val imap = src as com.sun.mail.imap.IMAPFolder
                // Prefer the IMAP MOVE extension (RFC 6851). Gmail supports it, and unlike a
                // COPY *into* Bin/Trash — which Gmail accepts on the server but answers with a
                // response JavaMail surfaces as a (spurious) error — UID MOVE completes cleanly.
                val movedViaExtension = imap.doCommand { protocol ->
                    if (!protocol.hasCapability("MOVE")) return@doCommand java.lang.Boolean.FALSE
                    val args = com.sun.mail.iap.Argument()
                    args.writeAtom(uid.toString())
                    args.writeString(com.sun.mail.imap.protocol.BASE64MailboxEncoder.encode(destFolderId))
                    val responses = protocol.command("UID MOVE", args)
                    val last = responses[responses.size - 1]
                    protocol.notifyResponseHandlers(responses)
                    protocol.handleResult(last) // throws on a NO/BAD server response
                    java.lang.Boolean.TRUE
                } as? Boolean ?: false

                if (!movedViaExtension) {
                    // Portable fallback for servers without MOVE: COPY to dest, then \Deleted + expunge.
                    val m = (imap as UIDFolder).getMessageByUID(uid)
                        ?: throw MailException.ProtocolError(404, "Message $uid not found in $folderId")
                    imap.copyMessages(arrayOf<Message>(m), dest)
                    m.setFlag(Flags.Flag.DELETED, true)
                }
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP move failed")
            } finally {
                runCatching { src.close(true) }
            }
        }
    }

    override suspend fun copyMessage(sessionId: String, messageId: String, destFolderId: String) {
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val (folderId, uid) = decodeId(messageId)
            val destId = imapFolderId(destFolderId)
            val dest = s.store.getFolder(destId)
            if (!dest.exists()) {
                throw MailException.ProtocolError(404, "Destination folder not found: $destId")
            }
            val src = s.store.getFolder(folderId)
            src.open(Folder.READ_ONLY)
            try {
                // IMAP COPY adds the message to dest, leaving the source intact. On Gmail
                // this applies the dest label without removing any existing label.
                val m = (src as UIDFolder).getMessageByUID(uid)
                    ?: throw MailException.ProtocolError(404, "Message $uid not found in $folderId")
                src.copyMessages(arrayOf<Message>(m), dest)
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP copy failed")
            } finally {
                runCatching { src.close(false) }
            }
        }
    }

    override suspend fun folderUidState(sessionId: String, folderId: String): MailFolderUidState =
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val folder = s.store.getFolder(folderId)
            folder.open(Folder.READ_ONLY)
            try {
                val uf = folder as UIDFolder
                val count = folder.messageCount
                val maxUid = if (count > 0) uf.getUID(folder.getMessage(count)) else 0L
                val uidNext = (folder as? com.sun.mail.imap.IMAPFolder)?.uidNext?.takeIf { it > 0 }
                MailFolderUidState(uidValidity = uf.uidValidity, uidNext = uidNext, maxUid = maxUid)
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP UID-state read failed")
            } finally {
                runCatching { folder.close(false) }
            }
        }

    override suspend fun listSince(
        sessionId: String,
        folderId: String,
        sinceUid: Long,
        max: Int,
    ): List<MailNewMessage> = withContext(Dispatchers.IO) {
        val s = session(sessionId)
        val folder = s.store.getFolder(folderId)
        folder.open(Folder.READ_ONLY)
        try {
            val uf = folder as UIDFolder
            // Inclusive range; getMessagesByUID can return the boundary message, so filter > sinceUid.
            val msgs = uf.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID)
            if (msgs.isEmpty()) return@withContext emptyList()
            folder.fetch(
                msgs,
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
            msgs.mapNotNull { m ->
                val uid = uf.getUID(m)
                if (uid <= sinceUid) null else MailNewMessage(toMailMessage(m, uf, folderId), uid)
            }.sortedBy { it.uid }.take(max)
        } catch (e: MessagingException) {
            throw MailException.ProtocolError(0, e.message ?: "IMAP poll failed")
        } finally {
            runCatching { folder.close(false) }
        }
    }

    override suspend fun search(
        sessionId: String,
        folderId: String,
        criteria: MailSearchCriteria,
        limit: Int,
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val term = buildSearchTerm(criteria) ?: return@withContext emptyList()
        val s = session(sessionId)
        val fid = imapFolderId(folderId)
        val folder = s.store.getFolder(fid)
        folder.open(Folder.READ_ONLY)
        try {
            val found = folder.search(term)
            if (found.isEmpty()) return@withContext emptyList()
            // SEARCH returns ascending message numbers (oldest→newest); keep only the newest
            // [limit] so a broad match doesn't fetch thousands of envelopes over the tunnel.
            val recent = if (found.size > limit) found.copyOfRange(found.size - limit, found.size) else found
            folder.fetch(
                recent,
                FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
            val uf = folder as UIDFolder
            recent.map { toMailMessage(it, uf, fid) }
                .sortedByDescending { it.timeSeconds }
        } catch (e: MessagingException) {
            throw MailException.ProtocolError(0, e.message ?: "IMAP search failed")
        } finally {
            runCatching { folder.close(false) }
        }
    }

    override suspend fun saveDraft(sessionId: String, mail: OutgoingMail): String =
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val p = s.params
            // A MimeMessage is built the same way as for send (reusing reply-threading), then
            // appended to Drafts flagged \Draft instead of going out over SMTP.
            val draftSession = Session.getInstance(buildSmtpProps(p))
            val reply = mail.inReplyToMessageId?.let { resolveReplyHeaders(s, it) }
            val msg = buildMimeMessage(draftSession, p, mail, reply?.first, reply?.second ?: emptyList())
            val drafts = findDraftsFolder(s.store)?.takeIf { it.exists() }
                ?: throw MailException.ProtocolError(404, "No Drafts folder on this account")
            drafts.open(Folder.READ_WRITE)
            try {
                msg.setFlag(Flags.Flag.DRAFT, true)
                msg.setFlag(Flags.Flag.SEEN, true)
                drafts.appendMessages(arrayOf<Message>(msg))
                drafts.fullName
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP draft append failed")
            } finally {
                runCatching { drafts.close(false) }
            }
        }

    override suspend fun createFolder(sessionId: String, name: String): String =
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val trimmed = name.trim()
            if (trimmed.isBlank()) throw MailException.ProtocolError(400, "Folder name is blank")
            val folder = s.store.getFolder(trimmed)
            try {
                if (folder.exists()) throw MailException.ProtocolError(409, "Folder already exists: $trimmed")
                if (!folder.create(Folder.HOLDS_MESSAGES)) {
                    throw MailException.ProtocolError(0, "Server refused to create folder: $trimmed")
                }
                folder.fullName
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP create-folder failed")
            }
        }

    override suspend fun deleteFolder(sessionId: String, folderId: String) {
        withContext(Dispatchers.IO) {
            val s = session(sessionId)
            val fid = imapFolderId(folderId)
            if (fid.equals("INBOX", ignoreCase = true)) {
                throw MailException.ProtocolError(400, "Refusing to delete INBOX")
            }
            val folder = s.store.getFolder(fid)
            try {
                if (!folder.exists()) throw MailException.ProtocolError(404, "Folder not found: $fid")
                // Refuse special-use system folders (Sent/Drafts/Trash/Spam/All Mail/…) — deleting
                // one breaks the account; only user folders/labels may be removed.
                val attrs = runCatching {
                    (folder as? com.sun.mail.imap.IMAPFolder)?.attributes?.toList()
                }.getOrNull().orEmpty()
                if (folderRole(folder.name, attrs) != MailFolderRole.NONE) {
                    throw MailException.ProtocolError(400, "Refusing to delete the system folder: $fid")
                }
                if (folder.isOpen) runCatching { folder.close(false) }
                if (!folder.delete(true)) {
                    throw MailException.ProtocolError(0, "Server refused to delete folder: $fid")
                }
            } catch (e: MessagingException) {
                throw MailException.ProtocolError(0, e.message ?: "IMAP delete-folder failed")
            }
        }
    }

    /**
     * Open the message's folder READ_WRITE, resolve it by UID, run [block], and close
     * (expunging when [expungeOnClose]). Shared by the flag/delete filter ops.
     */
    private suspend fun withMessageReadWrite(
        sessionId: String,
        messageId: String,
        expungeOnClose: Boolean,
        block: (Folder, Message) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val s = session(sessionId)
        val (folderId, uid) = decodeId(messageId)
        val folder = s.store.getFolder(folderId)
        folder.open(Folder.READ_WRITE)
        try {
            val m = (folder as UIDFolder).getMessageByUID(uid)
                ?: throw MailException.ProtocolError(404, "Message $uid not found in $folderId")
            block(folder, m)
        } catch (e: MessagingException) {
            throw MailException.ProtocolError(0, e.message ?: "IMAP update failed")
        } finally {
            runCatching { folder.close(expungeOnClose) }
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
            // Resolve reply-threading headers from the original message (if this is a reply),
            // so the outgoing message carries In-Reply-To / References and threads.
            val reply = mail.inReplyToMessageId?.let { resolveReplyHeaders(s, it) }
            val msg = buildMimeMessage(smtpSession, p, mail, reply?.first, reply?.second ?: emptyList())
            try {
                val transport = smtpSession.getTransport(if (smtpImplicitTls(p)) "smtps" else "smtp")
                try {
                    // Pass the creds — JavaMail authenticates only when the server
                    // advertises AUTH (so a no-auth relay/test sink still works);
                    // mail.smtp.auth is intentionally NOT forced on.
                    // Dial the SMTP host, which differs from the IMAP host on real
                    // providers (smtp.gmail.com vs imap.gmail.com); falls back to
                    // the IMAP host for self-hosted same-host setups.
                    transport.connect(smtpHost(p), p.smtpPort, p.username, p.password)
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

    /** Implicit-TLS SMTP iff the submission port is 465 (vs STARTTLS/plaintext on 587/25). */
    internal fun smtpImplicitTls(p: MailConnectParams.Imap): Boolean = p.smtpPort == 465

    /** SMTP host — the dedicated [MailConnectParams.Imap.smtpServer], or the IMAP host as a fallback. */
    internal fun smtpHost(p: MailConnectParams.Imap): String = p.smtpServer ?: p.server

    private fun session(sessionId: String): ImapSession {
        val s = sessions[sessionId]
            ?: throw MailException.SessionExpired("IMAP session $sessionId not found")
        // Gmail (and most IMAP servers) drop idle connections; reopen the Store on use
        // rather than failing with "Socket is closed". IMAPStore.isConnected() pings with a
        // NOOP so it detects a dead socket, and params still holds the creds + tunneled
        // socket factory, so reconnecting needs no re-prompt.
        val alive = try { s.store.isConnected() } catch (e: Exception) { false }
        if (!alive) {
            try {
                val p = s.params
                s.store.connect(p.server, p.port, p.username, p.password)
            } catch (e: Exception) {
                throw MailException.SessionExpired("IMAP session $sessionId reconnect failed: ${e.message}")
            }
        }
        return s
    }

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
     *
     * The implicit-TLS vs STARTTLS decision keys off the SMTP **port**, not the
     * account's [MailConnectParams.Imap.tls] flag: 465 is the conventional
     * implicit-TLS submission port, everything else (587 submission, 25 relay,
     * test sinks) is plaintext-with-opportunistic-STARTTLS. `tls` governs the IMAP
     * side, where a provider is commonly 993-implicit while its SMTP is 587-STARTTLS
     * (iCloud, Outlook) — so a single flag can't serve both.
     */
    internal fun buildSmtpProps(p: MailConnectParams.Imap): Properties = Properties().apply {
        // Register providers explicitly — Android strips META-INF/javamail.* so
        // auto-discovery fails on-device; without this getTransport() throws.
        this["mail.smtp.class"] = "com.sun.mail.smtp.SMTPTransport"
        this["mail.smtps.class"] = "com.sun.mail.smtp.SMTPSSLTransport"
        this["mail.smtp.timeout"] = TIMEOUT_MS
        this["mail.smtps.timeout"] = TIMEOUT_MS

        val sf = p.socketFactory
        if (smtpImplicitTls(p)) {
            // Implicit TLS (465). JavaMail's SocketFetcher creates the
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
            // STARTTLS submission (587) or plaintext relay/test (25).
            this["mail.transport.protocol"] = "smtp"
            this["mail.smtp.starttls.enable"] = "true"
            // For a TLS account, REQUIRE STARTTLS: a MITM that strips the
            // server's STARTTLS advertisement can otherwise downgrade the
            // session to plaintext and harvest the SMTP credentials
            // (STRIPTLS). Only an explicitly-plaintext account (tls = false,
            // e.g. a local relay / test sink) stays opportunistic.
            // (security-review #16)
            this["mail.smtp.starttls.required"] = p.tls.toString()
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
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
    ): MimeMessage = MimeMessage(smtpSession).apply {
        setFrom(fromAddress(p))
        setRecipients(Message.RecipientType.TO, toAddresses(mail.to))
        if (mail.cc.isNotEmpty()) setRecipients(Message.RecipientType.CC, toAddresses(mail.cc))
        if (mail.bcc.isNotEmpty()) setRecipients(Message.RecipientType.BCC, toAddresses(mail.bcc))
        setSubject(mail.subject, "UTF-8")
        // RFC 5322 threading: In-Reply-To = the parent's Message-ID; References = the
        // parent's References chain plus the parent's own Message-ID.
        if (inReplyTo != null) setHeader("In-Reply-To", inReplyTo)
        if (references.isNotEmpty()) setHeader("References", references.joinToString(" "))
        if (mail.attachments.isEmpty()) {
            setText(mail.bodyText, "UTF-8")
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { setText(mail.bodyText, "UTF-8") })
            for (a in mail.attachments) {
                mp.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = javax.activation.DataHandler(
                            javax.mail.util.ByteArrayDataSource(a.bytes, a.mimeType),
                        )
                        fileName = a.filename
                        disposition = javax.mail.Part.ATTACHMENT
                    },
                )
            }
            setContent(mp)
        }
        sentDate = Date()
        saveChanges()
    }

    /**
     * Read the parent message's `Message-ID` (and `References`) so a reply can thread.
     * Returns `(inReplyTo, references)` where references = the parent's References chain
     * plus its own Message-ID, or null if the parent or its Message-ID can't be read
     * (a reply without these headers still sends, just unthreaded). Best-effort.
     */
    private fun resolveReplyHeaders(s: ImapSession, messageId: String): Pair<String, List<String>>? =
        runCatching {
            val (folderId, uid) = decodeId(messageId)
            val folder = s.store.getFolder(imapFolderId(folderId))
            folder.open(Folder.READ_ONLY)
            try {
                val m = (folder as UIDFolder).getMessageByUID(uid) ?: return null
                val parentId = m.getHeader("Message-ID")?.firstOrNull()?.trim()?.ifBlank { null }
                    ?: return null
                val parentRefs = m.getHeader("References")?.firstOrNull()
                    ?.split(Regex("\\s+"))?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
                parentId to (parentRefs + parentId)
            } finally {
                runCatching { folder.close(false) }
            }
        }.getOrNull()

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

    /** Locate the Drafts folder: prefer the RFC 6154 `\Drafts` special-use attribute, then a name match. */
    private fun findDraftsFolder(store: Store): Folder? {
        val all = runCatching { store.defaultFolder.list("*").toList() }.getOrDefault(emptyList())
        all.firstOrNull { f ->
            runCatching {
                (f as? com.sun.mail.imap.IMAPFolder)?.attributes
                    ?.any { it.equals("\\Drafts", ignoreCase = true) } == true
            }.getOrDefault(false)
        }?.let { return it }
        val names = setOf("drafts", "draft")
        return all.firstOrNull {
            it.name.lowercase() in names || it.fullName.lowercase() in names
        }
    }

    companion object {
        private const val TAG = "ImapMailClient"
        private const val TIMEOUT_MS = "30000"

        /**
         * Compose a JavaMail [SearchTerm] from [c] by ANDing every set field, or null when
         * [c] is empty (the caller treats that as "no search"). Pure — unit-tested.
         */
        internal fun buildSearchTerm(c: MailSearchCriteria): SearchTerm? {
            if (c.isEmpty) return null
            val terms = mutableListOf<SearchTerm>()
            c.from?.let { terms += FromStringTerm(it) }
            c.to?.let { terms += RecipientStringTerm(Message.RecipientType.TO, it) }
            c.subject?.let { terms += SubjectTerm(it) }
            c.bodyText?.let { terms += BodyTerm(it) }
            if (c.unreadOnly) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
            c.sinceEpochSec?.let { terms += ReceivedDateTerm(ComparisonTerm.GE, Date(it * 1000)) }
            c.beforeEpochSec?.let { terms += ReceivedDateTerm(ComparisonTerm.LE, Date(it * 1000)) }
            return if (terms.size == 1) terms[0] else AndTerm(terms.toTypedArray())
        }

        /**
         * The 1-based IMAP message-number window for the most-recent [limit]
         * messages, skipping [offset] from the newest end (messages are numbered
         * 1=oldest .. [count]=newest). Returns null when there's nothing to fetch
         * (empty folder, or [offset] past the start). Clamps the low end to 1, so a
         * final short page returns the remaining oldest messages.
         */
        internal fun recentSlice(count: Int, limit: Int, offset: Int): IntRange? {
            if (count <= 0 || limit <= 0 || offset < 0) return null
            val end = count - offset
            if (end < 1) return null
            val start = maxOf(1, end - limit + 1)
            return start..end
        }

        /**
         * Encode a stable opaque message id as `"<folderFullName> <uid>"`. The
         * UID is IMAP's per-folder stable identifier; the folder name is needed
         * because [getMessageRaw] reopens the folder to fetch by UID. [decodeId]
         * splits on the LAST space, so folder names containing spaces ("Sent
         * Items 42") round-trip; the numeric UID never contains one. A printable
         * space (not a control byte) keeps the id valid as a JSON string on the
         * MCP read path.
         */
        /**
         * Map the cross-engine inbox sentinel ([MailFolder.INBOX_ID] = "0", Proton's id) to
         * IMAP's "INBOX". The MCP/read layer is engine-neutral and may pass "0" (e.g. the
         * list_mail_messages default), but IMAP has no folder named "0". Normalising here —
         * before envelope ids are minted from the folder — keeps every read/modify-by-id path
         * resolving to the real mailbox. Other folder ids (from listFolders) pass through.
         */
        internal fun imapFolderId(folderId: String): String =
            if (folderId == MailFolder.INBOX_ID) "INBOX" else folderId

        internal fun encodeId(folderId: String, uid: Long): String = "$folderId $uid"

        internal fun decodeId(messageId: String): Pair<String, Long> {
            val i = messageId.lastIndexOf(' ')
            require(i > 0 && i < messageId.length - 1) { "Malformed IMAP message id: $messageId" }
            val uid = messageId.substring(i + 1).toLongOrNull()
                ?: throw IllegalArgumentException("Malformed IMAP message id (uid): $messageId")
            return messageId.substring(0, i) to uid
        }

        /**
         * Classify an IMAP mailbox into an engine-neutral [MailFolderRole] from its leaf
         * [name] and its RFC 6154 special-use [attributes] (`\Sent`, `\Drafts`, `\Trash`,
         * `\Junk`, `\Flagged`, `\All`, `\Archive`) plus Gmail's non-standard `\Important`.
         * INBOX is matched by name (RFC 3501 reserves it; it carries no special-use flag).
         * Attribute matching is case-insensitive — servers vary.
         */
        internal fun folderRole(name: String, attributes: List<String>): MailFolderRole {
            if (name.equals("INBOX", ignoreCase = true)) return MailFolderRole.INBOX
            val attrs = attributes.mapTo(HashSet()) { it.lowercase() }
            return when {
                "\\sent" in attrs -> MailFolderRole.SENT
                "\\drafts" in attrs -> MailFolderRole.DRAFTS
                "\\trash" in attrs -> MailFolderRole.TRASH
                "\\junk" in attrs -> MailFolderRole.SPAM
                "\\flagged" in attrs -> MailFolderRole.STARRED   // Gmail "Starred"
                "\\important" in attrs -> MailFolderRole.IMPORTANT // Gmail (non-RFC) "Important"
                "\\all" in attrs -> MailFolderRole.ARCHIVE        // Gmail "All Mail"
                "\\archive" in attrs -> MailFolderRole.ARCHIVE
                else -> MailFolderRole.NONE
            }
        }

        /**
         * IMAP `\Noselect` (RFC 3501) mailboxes are containers only — they hold no messages,
         * so opening one throws. Gmail's `[Gmail]` parent is the canonical example. Such
         * folders are dropped from the list until the tree becomes hierarchical (1b).
         */
        internal fun folderSelectable(attributes: List<String>): Boolean =
            attributes.none { it.equals("\\Noselect", ignoreCase = true) }
    }
}
