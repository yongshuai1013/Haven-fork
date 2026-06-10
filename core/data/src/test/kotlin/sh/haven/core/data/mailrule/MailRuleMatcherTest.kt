package sh.haven.core.data.mailrule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure truth-table coverage of [MailRuleMatcher] — no Android, no I/O. */
class MailRuleMatcherTest {

    private fun msg(
        from: String = "alice@example.com",
        fromName: String = "Alice",
        to: List<String> = listOf("me@example.com"),
        subject: String = "Hello world",
        unread: Boolean = true,
        body: String? = null,
        attachmentNames: List<String>? = null,
        attachmentMimes: List<String>? = null,
        headers: Map<String, List<String>>? = null,
    ) = MatchableMessage(from, fromName, to, subject, unread, body, attachmentNames, attachmentMimes, headers)

    private fun crit(combinator: MatchCombinator = MatchCombinator.ALL, vararg c: MailCondition) =
        MailCriteria(combinator, c.toList())

    @Test
    fun tier1Conditions() {
        val m = msg()
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.From(StringOp.CONTAINS, "alice"))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.From(StringOp.CONTAINS, "Alice"))), m)) // name side
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.CONTAINS, "world"))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.EQUALS, "hello WORLD"))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.To(StringOp.CONTAINS, "me@"))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.IsUnread(true))), m))
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.IsUnread(false))), m))
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.CONTAINS, "absent"))), m))
    }

    @Test
    fun regexAndGlob() {
        val m = msg(subject = "Invoice #4815 for June")
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.REGEX, """invoice\s+#\d+"""))), m))
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.REGEX, """^\d+$"""))), m))
        // a malformed regex fails closed, never throws
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Subject(StringOp.REGEX, "("))), m))
        val a = msg(attachmentNames = listOf("report.pdf", "photo.JPG"))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.AttachmentName(StringOp.GLOB, "*.pdf"))), a))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.AttachmentName(StringOp.GLOB, "*.jpg"))), a)) // case-insensitive
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.AttachmentName(StringOp.GLOB, "*.png"))), a))
    }

    @Test
    fun tier2NeedsContentAndFailsClosedWhenMissing() {
        val bodyCond = MailCondition.Body(StringOp.CONTAINS, "wire transfer")
        assertTrue(MailRuleMatcher.requiresContent(crit(c = arrayOf(bodyCond))))
        assertFalse(MailRuleMatcher.requiresContent(crit(c = arrayOf(MailCondition.Subject(StringOp.CONTAINS, "x")))))
        // content not fetched (body == null) -> no match, not a spurious fire
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(bodyCond)), msg(body = null)))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(bodyCond)), msg(body = "please send a wire transfer today")))
    }

    @Test
    fun attachmentAndHeaderConditions() {
        val m = msg(
            attachmentNames = listOf("q3.xlsx"),
            attachmentMimes = listOf("application/vnd.ms-excel"),
            headers = mapOf("x-priority" to listOf("1"), "list-id" to listOf("<dev.example.com>")),
        )
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.HasAttachment(true))), m))
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.HasAttachment(false))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.AttachmentMime(StringOp.CONTAINS, "excel"))), m))
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Header("X-Priority", StringOp.EQUALS, "1"))), m))
        assertFalse(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.Header("X-Missing", StringOp.CONTAINS, "x"))), m))
        // hasAttachment=false when names list is empty
        assertTrue(MailRuleMatcher.matches(crit(c = arrayOf(MailCondition.HasAttachment(false))), msg(attachmentNames = emptyList())))
    }

    @Test
    fun combinatorsAndEmpty() {
        val m = msg()
        val hit = MailCondition.From(StringOp.CONTAINS, "alice")
        val miss = MailCondition.Subject(StringOp.CONTAINS, "absent")
        assertFalse("ALL requires every condition", MailRuleMatcher.matches(crit(MatchCombinator.ALL, hit, miss), m))
        assertTrue("ANY needs one", MailRuleMatcher.matches(crit(MatchCombinator.ANY, hit, miss), m))
        assertFalse("ANY all-miss", MailRuleMatcher.matches(crit(MatchCombinator.ANY, miss, miss), m))
        // an empty condition list NEVER matches (safety — must not fire on everything)
        assertFalse(MailRuleMatcher.matches(MailCriteria(MatchCombinator.ALL, emptyList()), m))
        assertFalse(MailRuleMatcher.matches(MailCriteria(MatchCombinator.ANY, emptyList()), m))
    }
}
