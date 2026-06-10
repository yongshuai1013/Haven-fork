package sh.haven.core.data.mailrule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage of [MailRuleJson]. Needs the real org.json (added as a test dep) —
 * the android.jar stub returns defaults under isReturnDefaultValues.
 */
class MailRuleJsonTest {

    @Test
    fun criteriaRoundTripAllConditionTypes() {
        val c = MailCriteria(
            combinator = MatchCombinator.ANY,
            conditions = listOf(
                MailCondition.From(StringOp.CONTAINS, "boss@corp"),
                MailCondition.To(StringOp.EQUALS, "me@x"),
                MailCondition.Subject(StringOp.REGEX, """\[ALERT\]"""),
                MailCondition.IsUnread(true),
                MailCondition.Body(StringOp.CONTAINS, "invoice"),
                MailCondition.HasAttachment(true),
                MailCondition.AttachmentName(StringOp.GLOB, "*.pdf"),
                MailCondition.AttachmentMime(StringOp.CONTAINS, "pdf"),
                MailCondition.Header("List-Id", StringOp.CONTAINS, "dev.example"),
            ),
        )
        val back = MailRuleJson.criteriaFromJson(MailRuleJson.criteriaToJson(c))
        assertEquals(c, back)
    }

    @Test
    fun actionsRoundTripAllTypes() {
        val actions = listOf(
            MailRuleAction.SaveAttachments("local", "/sdcard/x", nameGlob = "*.pdf"),
            MailRuleAction.RunCommand("process {attachmentPath}", background = true),
            MailRuleAction.SendToAgent("matched {subject} from {from}", targetSessionId = "s1"),
            MailRuleAction.Notify("New mail", "{subject}"),
            MailRuleAction.ImapFilter(ImapFilterOp.MOVE, destFolderId = "Done"),
            MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ),
            MailRuleAction.Forward(listOf("a@x", "b@y"), template = "FYI"),
            MailRuleAction.InvokeMcpTool("present_media", """{"url":"{attachmentPath}"}"""),
        )
        val back = MailRuleJson.actionsFromJson(MailRuleJson.actionsToJson(actions))
        assertEquals(actions, back)
    }

    @Test
    fun unknownTypesAreSkippedNotThrown() {
        val json = """[{"type":"from_the_future","x":1},{"type":"notify","titleTemplate":"t","bodyTemplate":"b"}]"""
        val actions = MailRuleJson.actionsFromJson(json)
        assertEquals(1, actions.size)
        assertTrue(actions.first() is MailRuleAction.Notify)
    }

    @Test
    fun staticDestructiveClassification() {
        assertEquals(true, MailRuleAction.RunCommand("x").staticDestructive())
        assertEquals(true, MailRuleAction.Forward(listOf("a@x")).staticDestructive())
        assertEquals(true, MailRuleAction.ImapFilter(ImapFilterOp.DELETE).staticDestructive())
        assertEquals(true, MailRuleAction.ImapFilter(ImapFilterOp.MOVE).staticDestructive())
        assertEquals(false, MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ).staticDestructive())
        assertEquals(false, MailRuleAction.Notify("t", "b").staticDestructive())
        assertEquals(false, MailRuleAction.SaveAttachments("local", "/x").staticDestructive())
        // invoke_mcp_tool posture is resolved at run time from the tool's ConsentLevel
        assertEquals(null, MailRuleAction.InvokeMcpTool("x", "{}").staticDestructive())
    }
}
