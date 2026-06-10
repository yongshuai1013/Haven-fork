package sh.haven.core.data.mailrule

import org.json.JSONArray
import org.json.JSONObject

/**
 * The pure, engine-neutral domain model for inbound-email automation ("Mail Rules").
 *
 * These types deliberately depend on nothing from `core/mail` or Android: a rule's
 * criteria are evaluated against a [MatchableMessage] (a flattened view the engine
 * builds from the IMAP envelope, plus optional parsed body/attachment/header content),
 * and actions are plain data the `app`-layer executor interprets. Criteria and action
 * lists persist as JSON strings on [sh.haven.core.data.db.entities.MailRule] (the
 * codebase convention — no Room TypeConverters); [MailRuleJson] is the (de)serialiser.
 */

/**
 * A flattened, matcher-ready view of one message. Tier-1 fields come from the IMAP
 * envelope (always available); the Tier-2 fields ([bodyText], [attachmentNames],
 * [attachmentMimes], [headers]) are null until the engine fetches the raw RFC822 and
 * parses it — which it only does when a rule actually has a Tier-2 condition.
 */
data class MatchableMessage(
    val fromAddress: String,
    val fromName: String,
    val toAddresses: List<String>,
    val subject: String,
    val unread: Boolean,
    val bodyText: String? = null,
    val attachmentNames: List<String>? = null,
    val attachmentMimes: List<String>? = null,
    val headers: Map<String, List<String>>? = null,
)

/** How a list of conditions combines. */
enum class MatchCombinator { ALL, ANY }

/** String comparison applied by a condition. GLOB uses `*`/`?` wildcards. */
enum class StringOp { CONTAINS, EQUALS, REGEX, GLOB }

/**
 * One predicate over a [MatchableMessage]. [tier2] is true when evaluating it needs
 * the parsed body/attachment/header content (so the engine must fetch+parse the raw
 * message first); Tier-1 conditions evaluate on the envelope alone.
 */
sealed interface MailCondition {
    val tier2: Boolean

    data class From(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = false
    }
    data class To(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = false
    }
    data class Subject(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = false
    }
    data class IsUnread(val value: Boolean) : MailCondition {
        override val tier2 get() = false
    }
    data class Body(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = true
    }
    data class HasAttachment(val value: Boolean) : MailCondition {
        override val tier2 get() = true
    }
    data class AttachmentName(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = true
    }
    data class AttachmentMime(val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = true
    }
    data class Header(val name: String, val op: StringOp, val value: String) : MailCondition {
        override val tier2 get() = true
    }
}

/** A rule's match criteria: a [combinator] over [conditions]. An empty list never matches. */
data class MailCriteria(
    val combinator: MatchCombinator = MatchCombinator.ALL,
    val conditions: List<MailCondition> = emptyList(),
)

/** IMAP "filter" operations. MOVE/DELETE are destructive (see background-safety posture). */
enum class ImapFilterOp { MARK_READ, MARK_UNREAD, SET_FLAGGED, UNSET_FLAGGED, MOVE, DELETE }

/**
 * One action a rule runs when it matches. The curated set is typed; [InvokeMcpTool] is
 * the generic escape hatch that triggers any Haven MCP tool (its background-safety
 * posture is derived at run time from the target tool's ConsentLevel, not stored here).
 */
sealed interface MailRuleAction {
    /** Save matching attachments to a Haven filesystem backend. */
    data class SaveAttachments(
        val destProfileId: String,
        val destDir: String,
        val nameGlob: String? = null,
        val mimeGlob: String? = null,
    ) : MailRuleAction

    /** Run a fixed command template (placeholders shell-escaped at fire time) in proot. */
    data class RunCommand(val template: String, val background: Boolean = false) : MailRuleAction

    /** Deliver a one-line summary into an agent REPL terminal session. */
    data class SendToAgent(val messageTemplate: String, val targetSessionId: String? = null) : MailRuleAction

    /** Raise a system notification. */
    data class Notify(val titleTemplate: String, val bodyTemplate: String) : MailRuleAction

    /** Apply an IMAP filter operation to the message. */
    data class ImapFilter(val op: ImapFilterOp, val destFolderId: String? = null) : MailRuleAction

    /** Forward the message to other recipients (reuses the SMTP send path). */
    data class Forward(val to: List<String>, val template: String? = null) : MailRuleAction

    /** Generic: invoke any Haven MCP tool with a templated argument JSON. */
    data class InvokeMcpTool(val toolName: String, val argsTemplateJson: String) : MailRuleAction
}

/**
 * Static destructive classification of the curated action set, used as the default
 * background-safety posture. Returns null for [MailRuleAction.InvokeMcpTool] — whose
 * posture the executor resolves from the target tool's ConsentLevel at run time.
 */
fun MailRuleAction.staticDestructive(): Boolean? = when (this) {
    is MailRuleAction.RunCommand -> true
    is MailRuleAction.Forward -> true
    is MailRuleAction.ImapFilter -> op == ImapFilterOp.MOVE || op == ImapFilterOp.DELETE
    is MailRuleAction.SaveAttachments,
    is MailRuleAction.SendToAgent,
    is MailRuleAction.Notify,
    -> false
    is MailRuleAction.InvokeMcpTool -> null
}

/**
 * (De)serialises [MailCriteria] and `List<MailRuleAction>` to/from the JSON strings
 * stored on the rule row. Each condition/action carries a `"type"` discriminator.
 * Tolerant on read: an unknown type or malformed entry is skipped rather than throwing,
 * so a forward-compatible rule written by a newer build never crashes an older parser.
 */
object MailRuleJson {

    // ---- criteria ----

    fun criteriaToJson(c: MailCriteria): String = JSONObject().apply {
        put("combinator", c.combinator.name)
        put("conditions", JSONArray().apply { c.conditions.forEach { put(conditionToJson(it)) } })
    }.toString()

    fun criteriaFromJson(json: String): MailCriteria {
        val o = JSONObject(json)
        val combinator = runCatching { MatchCombinator.valueOf(o.optString("combinator", "ALL")) }
            .getOrDefault(MatchCombinator.ALL)
        val arr = o.optJSONArray("conditions") ?: JSONArray()
        val conditions = (0 until arr.length()).mapNotNull { conditionFromJson(arr.getJSONObject(it)) }
        return MailCriteria(combinator, conditions)
    }

    private fun conditionToJson(c: MailCondition): JSONObject = JSONObject().apply {
        when (c) {
            is MailCondition.From -> put("type", "from").put("op", c.op.name).put("value", c.value)
            is MailCondition.To -> put("type", "to").put("op", c.op.name).put("value", c.value)
            is MailCondition.Subject -> put("type", "subject").put("op", c.op.name).put("value", c.value)
            is MailCondition.IsUnread -> put("type", "is_unread").put("value", c.value)
            is MailCondition.Body -> put("type", "body").put("op", c.op.name).put("value", c.value)
            is MailCondition.HasAttachment -> put("type", "has_attachment").put("value", c.value)
            is MailCondition.AttachmentName -> put("type", "attachment_name").put("op", c.op.name).put("value", c.value)
            is MailCondition.AttachmentMime -> put("type", "attachment_mime").put("op", c.op.name).put("value", c.value)
            is MailCondition.Header -> put("type", "header").put("name", c.name).put("op", c.op.name).put("value", c.value)
        }
    }

    private fun conditionFromJson(o: JSONObject): MailCondition? {
        fun op() = runCatching { StringOp.valueOf(o.getString("op")) }.getOrDefault(StringOp.CONTAINS)
        val value = o.optString("value")
        return when (o.optString("type")) {
            "from" -> MailCondition.From(op(), value)
            "to" -> MailCondition.To(op(), value)
            "subject" -> MailCondition.Subject(op(), value)
            "is_unread" -> MailCondition.IsUnread(o.optBoolean("value"))
            "body" -> MailCondition.Body(op(), value)
            "has_attachment" -> MailCondition.HasAttachment(o.optBoolean("value"))
            "attachment_name" -> MailCondition.AttachmentName(op(), value)
            "attachment_mime" -> MailCondition.AttachmentMime(op(), value)
            "header" -> MailCondition.Header(o.optString("name"), op(), value)
            else -> null
        }
    }

    // ---- actions ----

    fun actionsToJson(actions: List<MailRuleAction>): String =
        JSONArray().apply { actions.forEach { put(actionToJson(it)) } }.toString()

    fun actionsFromJson(json: String): List<MailRuleAction> {
        val arr = JSONArray(json)
        return (0 until arr.length()).mapNotNull { actionFromJson(arr.getJSONObject(it)) }
    }

    private fun actionToJson(a: MailRuleAction): JSONObject = JSONObject().apply {
        when (a) {
            is MailRuleAction.SaveAttachments -> {
                put("type", "save_attachments").put("destProfileId", a.destProfileId).put("destDir", a.destDir)
                a.nameGlob?.let { put("nameGlob", it) }
                a.mimeGlob?.let { put("mimeGlob", it) }
            }
            is MailRuleAction.RunCommand -> put("type", "run_command").put("template", a.template).put("background", a.background)
            is MailRuleAction.SendToAgent -> {
                put("type", "send_to_agent").put("messageTemplate", a.messageTemplate)
                a.targetSessionId?.let { put("targetSessionId", it) }
            }
            is MailRuleAction.Notify -> put("type", "notify").put("titleTemplate", a.titleTemplate).put("bodyTemplate", a.bodyTemplate)
            is MailRuleAction.ImapFilter -> {
                put("type", "imap_filter").put("op", a.op.name)
                a.destFolderId?.let { put("destFolderId", it) }
            }
            is MailRuleAction.Forward -> put("type", "forward").put("to", JSONArray(a.to)).apply { a.template?.let { put("template", it) } }
            is MailRuleAction.InvokeMcpTool -> put("type", "invoke_mcp_tool").put("toolName", a.toolName).put("argsTemplateJson", a.argsTemplateJson)
        }
    }

    private fun actionFromJson(o: JSONObject): MailRuleAction? = when (o.optString("type")) {
        "save_attachments" -> MailRuleAction.SaveAttachments(
            o.optString("destProfileId"), o.optString("destDir"),
            o.optString("nameGlob").ifBlank { null }, o.optString("mimeGlob").ifBlank { null },
        )
        "run_command" -> MailRuleAction.RunCommand(o.optString("template"), o.optBoolean("background"))
        "send_to_agent" -> MailRuleAction.SendToAgent(o.optString("messageTemplate"), o.optString("targetSessionId").ifBlank { null })
        "notify" -> MailRuleAction.Notify(o.optString("titleTemplate"), o.optString("bodyTemplate"))
        "imap_filter" -> runCatching { ImapFilterOp.valueOf(o.getString("op")) }.getOrNull()
            ?.let { MailRuleAction.ImapFilter(it, o.optString("destFolderId").ifBlank { null }) }
        "forward" -> MailRuleAction.Forward(
            (o.optJSONArray("to") ?: JSONArray()).let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            o.optString("template").ifBlank { null },
        )
        "invoke_mcp_tool" -> MailRuleAction.InvokeMcpTool(o.optString("toolName"), o.optString("argsTemplateJson").ifBlank { "{}" })
        else -> null
    }
}
