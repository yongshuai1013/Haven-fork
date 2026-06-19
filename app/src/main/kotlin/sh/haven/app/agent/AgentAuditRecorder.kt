package sh.haven.app.agent

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.app.R
import sh.haven.core.data.db.AgentAuditEventDao
import sh.haven.core.data.db.entities.AgentAuditEvent
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AgentAuditRecorder"

/** Soft cap on rows kept in the audit table. */
private const val MAX_EVENTS = 500

/** Time window beyond which events are dropped on next trim. */
private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** Trim runs once per [TRIM_EVERY_N] inserts; cheap and amortised. */
private const val TRIM_EVERY_N = 32

/**
 * Records every JSON-RPC call that crosses the agent transport into a
 * dedicated Room table, with arguments redacted and results summarised
 * down to a single line. The recorder is the sole on-disk witness of
 * agent activity — the brand promise from VISION.md (§85, §117) is
 * "Haven is the dashboard, not a black box," and this is the table that
 * dashboard reads from.
 *
 * Inserts are fire-and-forget on an internal IO scope so the request
 * handler is never blocked, and the recorder owns its own redaction so
 * callers cannot accidentally pass plaintext secrets through.
 */
@Singleton
class AgentAuditRecorder @Inject constructor(
    private val dao: AgentAuditEventDao,
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var insertsSinceTrim = 0

    private val _lastEventAt = MutableStateFlow<Long?>(null)
    val lastEventAt: StateFlow<Long?> = _lastEventAt.asStateFlow()

    /**
     * Record one completed RPC. Safe to call from any thread; the actual
     * insert happens on [scope]. Failures are logged and swallowed —
     * audit recording must never break the request path.
     */
    fun record(
        method: String,
        toolName: String?,
        rawArgs: JSONObject?,
        result: JSONObject?,
        durationMs: Long,
        outcome: AgentAuditEvent.Outcome,
        errorMessage: String?,
        clientHint: String?,
    ) {
        val event = AgentAuditEvent(
            timestamp = System.currentTimeMillis(),
            clientHint = clientHint?.take(120),
            method = method,
            toolName = toolName,
            argsJson = rawArgs?.let { redactJson(it).toString() }?.take(2_000),
            resultSummary = summariseResult(toolName, result, context)?.take(240),
            durationMs = durationMs,
            outcome = outcome,
            errorMessage = errorMessage?.take(500),
        )
        _lastEventAt.value = event.timestamp
        scope.launch {
            try {
                dao.insert(event)
                insertsSinceTrim++
                if (insertsSinceTrim >= TRIM_EVERY_N) {
                    insertsSinceTrim = 0
                    val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                    dao.trim(olderThan = cutoff, keepNewest = MAX_EVENTS)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "audit insert failed: ${e.message}")
            }
        }
    }

    /** Manual wipe — used by the "Clear history" button in the UI. */
    fun clearAll() {
        scope.launch {
            try { dao.deleteAll() } catch (e: Throwable) {
                Log.w(TAG, "audit deleteAll failed: ${e.message}")
            }
        }
    }
}

// --- Redaction ---

/**
 * Field-name patterns that always have their values replaced with
 * `<redacted>`. Matched case-insensitively as a substring of the JSON
 * key, so `password`, `sshPassword`, `proxyPassword`, etc. all hit. We
 * deliberately do **not** match the bare word "key" — `keyId`,
 * `publicKey`, `fingerprint` are common safe identifiers — but we do
 * match `privateKey`, `apiKey`, and friends explicitly.
 */
private val SECRET_KEY_PATTERNS = listOf(
    "password", "passwd", "secret", "token", "credential",
    "apikey", "api_key", "privatekey", "private_key", "passphrase",
)

private fun isSecretKey(name: String): Boolean {
    val lower = name.lowercase()
    return SECRET_KEY_PATTERNS.any { it in lower }
}

/**
 * Walk a JSON tree and return a copy with secret-shaped values replaced
 * by the literal string `<redacted>`. The original is not mutated.
 */
internal fun redactJson(input: JSONObject): JSONObject {
    val out = JSONObject()
    val keys = input.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val v = input.opt(k)
        out.put(k, if (isSecretKey(k)) "<redacted>" else redactValue(v))
    }
    return out
}

private fun redactValue(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> redactJson(v)
    is JSONArray -> {
        val arr = JSONArray()
        for (i in 0 until v.length()) arr.put(redactValue(v.opt(i)))
        arr
    }
    else -> v
}

// --- Result summarisation ---

/**
 * Reduce a tool result to a single human-readable line. The audit log
 * records that an agent *asked*, not what it received — if a user wants
 * to know exactly what data left Haven, they need to look at the
 * source-of-truth views (connection list, etc.), not at this log.
 */
private fun summariseResult(toolName: String?, result: JSONObject?, ctx: Context): String? {
    if (result == null) return null
    return when (toolName) {
        "list_connections" -> result.optInt("count", -1).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_connections, it) }
        "list_sessions" -> result.optInt("count", -1).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_sessions, it) }
        "list_rclone_remotes" -> result.optInt("count", -1).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_remotes, it) }
        "list_rclone_directory" -> {
            val n = result.optInt("count", -1)
            val remote = result.optString("remote", "")
            val path = result.optString("path", "")
            if (n >= 0) ctx.getString(R.string.agent_summary_entries_at, n, remote, path) else null
        }
        "list_directory" -> {
            val n = result.optInt("count", -1)
            val backend = result.optString("backend", "")
            val path = result.optString("path", "")
            if (n >= 0) ctx.getString(R.string.agent_summary_entries_via, n, backend, path) else null
        }
        "get_app_info" -> result.optString("version", "").takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.agent_summary_version, it) }
        "play_file" -> result.optString("mimeType", "").takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.agent_summary_dispatched, it) }
        "navigate_sftp_browser" -> {
            // Pure identifiers (profileId:path) — nothing to translate.
            val pid = result.optString("profileId", "")
            val path = result.optString("path", "")
            if (pid.isNotEmpty()) "$pid:$path" else null
        }
        "read_terminal_scrollback" -> result.optInt("byteCount", -1).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_bytes, it) }
        "disconnect_profile" -> ctx.getString(R.string.agent_summary_disconnected)
        "add_port_forward" -> {
            val activated = result.optBoolean("activated", false)
            val base = ctx.getString(if (activated) R.string.agent_summary_pf_saved_activated else R.string.agent_summary_pf_saved)
            if (result.has("actualBoundPort")) ctx.getString(R.string.agent_summary_pf_bound, base, result.optInt("actualBoundPort")) else base
        }
        "remove_port_forward" -> ctx.getString(if (result.optBoolean("deactivated", false)) R.string.agent_summary_pf_removed_deactivated else R.string.agent_summary_pf_removed)
        "upload_file_to_sftp" -> result.optLong("bytesUploaded", -1L).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_uploaded_bytes, it) }
        "serve_file" -> {
            val n = result.optLong("size", -1L)
            val backend = result.optString("backend", "")
            if (n >= 0) ctx.getString(R.string.agent_summary_served_bytes, n, backend) else null
        }
        "delete_sftp_file" -> ctx.getString(R.string.agent_summary_deleted)
        "upload_file" -> {
            val n = result.optLong("bytesUploaded", -1L)
            val backend = result.optString("backend", "")
            if (n >= 0) ctx.getString(R.string.agent_summary_uploaded_bytes_via, n, backend) else null
        }
        "delete_file" -> result.optString("backend", "").takeIf { it.isNotEmpty() }?.let { ctx.getString(R.string.agent_summary_deleted_via, it) } ?: ctx.getString(R.string.agent_summary_deleted)
        "send_terminal_input" -> result.optInt("bytesSent", -1).takeIf { it >= 0 }?.let { ctx.getString(R.string.agent_summary_bytes_typed, it) }
        "convert_file" -> {
            val size = result.optLong("sizeBytes", -1L)
            val ms = result.optLong("durationMs", -1L)
            if (size >= 0 && ms >= 0) ctx.getString(R.string.agent_summary_converted, size / 1024, ms) else null
        }
        "set_terminal_font_from_url" -> result.optLong("bytesDownloaded", -1L).takeIf { it >= 0 }?.let {
            ctx.getString(R.string.agent_summary_kib_installed, it / 1024)
        }
        "open_local_shell" -> ctx.getString(if (result.optBoolean("reused", false)) R.string.agent_summary_reused_existing else R.string.agent_summary_opened)
        "open_developer_settings" -> ctx.getString(R.string.agent_summary_opened)
        "install_apk_from_url" -> result.optLong("bytesDownloaded", -1L).takeIf { it >= 0 }?.let {
            ctx.getString(R.string.agent_summary_mib_installed, it / 1024 / 1024)
        }
        "enable_wireless_adb" -> {
            val ip = result.optString("ip", "")
            val port = result.optInt("port", -1)
            if (ip.isNotEmpty() && port > 0) ctx.getString(R.string.agent_summary_enabled_at, ip, port) else ctx.getString(R.string.agent_summary_enabled)
        }
        else -> null
    } ?: ctx.getString(R.string.agent_summary_ok)
}
