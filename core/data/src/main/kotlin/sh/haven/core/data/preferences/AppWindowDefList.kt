package sh.haven.core.data.preferences

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Who created a saved app-window definition. */
enum class AppWindowOrigin(val id: String) {
    USER("user"),
    AGENT("agent");

    companion object {
        fun fromId(id: String): AppWindowOrigin = entries.find { it.id == id } ?: USER
    }
}

/**
 * A saved single-app window: a [label] plus the guest shell [command] a cage
 * kiosk runs (e.g. "imv /root/x.png"). Created by the user in Desktop
 * settings, or recorded automatically when the agent launches one via
 * `present_app` — so either actor's windows are restartable from the same
 * list. Mirrors the [ToolbarLayout] JSON-in-DataStore precedent.
 */
data class AppWindowDef(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
    val createdBy: AppWindowOrigin = AppWindowOrigin.USER,
    val lastUsed: Long = System.currentTimeMillis(),
)

/** Persisted ordered list of [AppWindowDef], JSON-encoded into DataStore. */
data class AppWindowDefList(val items: List<AppWindowDef>) {

    fun toJson(): String {
        val arr = JSONArray()
        for (d in items) {
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("label", d.label)
                    put("command", d.command)
                    put("createdBy", d.createdBy.id)
                    put("lastUsed", d.lastUsed)
                },
            )
        }
        return arr.toString()
    }

    companion object {
        val EMPTY = AppWindowDefList(emptyList())

        fun fromJson(json: String): AppWindowDefList = try {
            val arr = JSONArray(json)
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val command = o.optString("command", "")
                if (command.isEmpty()) return@mapNotNull null
                AppWindowDef(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    label = o.optString("label", command),
                    command = command,
                    createdBy = AppWindowOrigin.fromId(o.optString("createdBy", "user")),
                    lastUsed = o.optLong("lastUsed", 0L),
                )
            }
            AppWindowDefList(items)
        } catch (_: Exception) {
            EMPTY
        }
    }
}
