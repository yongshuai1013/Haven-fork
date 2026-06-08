package sh.haven.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.net.SocketFactory

/**
 * CP-6 (Stage 2a) — server-less coverage of [ImapMailClient]'s send plumbing:
 * the SMTP property selection (the CP-5 base-socket / fail-closed lesson applied
 * to SMTP) and the outgoing [javax.mail.internet.MimeMessage] construction. The
 * live send round-trip is verified over MCP against a real SMTP sink in CP-6's
 * device test (the android-mail transport path is the same proven by CP-0).
 */
class ImapMailClientSendTest {

    private val client = ImapMailClient()

    /** A never-dialed stub — these tests only inspect props/headers, never connect. */
    private val stubFactory = object : SocketFactory() {
        override fun createSocket(): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, lh: InetAddress?, lp: Int): Socket =
            throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(a: InetAddress?, p: Int, la: InetAddress?, lp: Int): Socket =
            throw UnsupportedOperationException()
    }

    private fun imapParams(tls: Boolean, smtpPort: Int) = MailConnectParams.Imap(
        username = "alice@example.com",
        password = "app-password",
        server = "mail.example.com",
        port = 993,
        smtpPort = smtpPort,
        tls = tls,
        socketFactory = stubFactory,
    )

    @Test
    fun implicitTlsRoutesBaseSocketThroughTunnelAndFailsClosed() {
        val props = client.buildSmtpProps(imapParams(tls = true, smtpPort = 465))
        assertEquals("smtps", props["mail.transport.protocol"])
        // BASE factory (not just the .ssl wrap factory) → no clearnet leak.
        assertSame(stubFactory, props["mail.smtps.socketFactory"])
        assertEquals("false", props["mail.smtps.socketFactory.fallback"])
        // The wrap-only key must NOT be the one carrying the tunnel.
        assertTrue(props["mail.smtps.ssl.socketFactory"] == null)
    }

    @Test
    fun nonTlsUsesStarttlsAndBaseSocketFactory() {
        val props = client.buildSmtpProps(imapParams(tls = false, smtpPort = 587))
        assertEquals("smtp", props["mail.transport.protocol"])
        assertEquals("true", props["mail.smtp.starttls.enable"])
        assertSame(stubFactory, props["mail.smtp.socketFactory"])
        assertEquals("false", props["mail.smtp.socketFactory.fallback"])
    }

    @Test
    fun buildsMimeMessageWithHeadersBodyAndMessageId() {
        val p = imapParams(tls = true, smtpPort = 465)
        val session = Session.getInstance(client.buildSmtpProps(p))
        val msg = client.buildMimeMessage(
            session,
            p,
            OutgoingMail(
                to = listOf("bob@example.org"),
                cc = listOf("carol@example.org"),
                subject = "Hello",
                bodyText = "Body text",
            ),
        )
        assertEquals("Hello", msg.subject)
        assertEquals("alice@example.com", (msg.from.first() as InternetAddress).address)
        assertEquals(
            "bob@example.org",
            (msg.getRecipients(Message.RecipientType.TO).first() as InternetAddress).address,
        )
        assertEquals(
            "carol@example.org",
            (msg.getRecipients(Message.RecipientType.CC).first() as InternetAddress).address,
        )
        assertTrue(msg.getRecipients(Message.RecipientType.BCC).isNullOrEmpty())
        assertEquals("Body text", msg.content.toString().trim())
        // saveChanges() must have assigned a Message-ID.
        assertNotNull(msg.messageID)
    }

    @Test
    fun fromSynthesisesDomainForBareUsername() {
        val p = imapParams(tls = false, smtpPort = 587).copy(username = "droid")
        val session = Session.getInstance(client.buildSmtpProps(p))
        val msg = client.buildMimeMessage(
            session, p, OutgoingMail(to = listOf("x@y.z"), subject = "s", bodyText = "b"),
        )
        assertEquals("droid@mail.example.com", (msg.from.first() as InternetAddress).address)
    }
}
