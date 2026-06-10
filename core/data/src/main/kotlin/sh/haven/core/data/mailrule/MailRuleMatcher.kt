package sh.haven.core.data.mailrule

/**
 * Pure evaluation of a [MailCriteria] against a [MatchableMessage]. No Android, no IMAP,
 * no I/O — fully unit-testable. The engine builds the [MatchableMessage] (fetching +
 * parsing the raw RFC822 only when [requiresContent] is true) and calls [matches].
 *
 * Safety: an empty condition list NEVER matches (a rule with no conditions must not fire
 * on every message). Tier-2 conditions evaluated against a message whose content wasn't
 * fetched (the relevant field is null) are treated as non-matching, so a missing fetch
 * fails closed rather than firing spuriously.
 */
object MailRuleMatcher {

    /** True when any condition needs parsed body/attachment/header content (a raw fetch). */
    fun requiresContent(criteria: MailCriteria): Boolean = criteria.conditions.any { it.tier2 }

    fun matches(criteria: MailCriteria, msg: MatchableMessage): Boolean {
        if (criteria.conditions.isEmpty()) return false
        val results = criteria.conditions.asSequence().map { evaluate(it, msg) }
        return when (criteria.combinator) {
            MatchCombinator.ALL -> results.all { it }
            MatchCombinator.ANY -> results.any { it }
        }
    }

    private fun evaluate(c: MailCondition, m: MatchableMessage): Boolean = when (c) {
        is MailCondition.From ->
            strOp(c.op, m.fromAddress, c.value) || strOp(c.op, m.fromName, c.value)
        is MailCondition.To ->
            m.toAddresses.any { strOp(c.op, it, c.value) }
        is MailCondition.Subject ->
            strOp(c.op, m.subject, c.value)
        is MailCondition.IsUnread ->
            m.unread == c.value
        is MailCondition.Body ->
            m.bodyText?.let { strOp(c.op, it, c.value) } ?: false
        is MailCondition.HasAttachment ->
            m.attachmentNames?.let { it.isNotEmpty() == c.value } ?: false
        is MailCondition.AttachmentName ->
            m.attachmentNames?.any { strOp(c.op, it, c.value) } ?: false
        is MailCondition.AttachmentMime ->
            m.attachmentMimes?.any { strOp(c.op, it, c.value) } ?: false
        is MailCondition.Header ->
            m.headers?.get(c.name.lowercase())?.any { strOp(c.op, it, c.value) } ?: false
    }

    private fun strOp(op: StringOp, haystack: String, needle: String): Boolean = when (op) {
        StringOp.CONTAINS -> haystack.contains(needle, ignoreCase = true)
        StringOp.EQUALS -> haystack.equals(needle, ignoreCase = true)
        StringOp.REGEX -> runCatching { Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(haystack) }
            .getOrDefault(false)
        StringOp.GLOB -> globToRegex(needle).matches(haystack)
    }

    /** `*` → any run, `?` → one char; the rest is literal. Case-insensitive, anchored. */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}
