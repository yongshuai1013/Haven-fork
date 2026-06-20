package sh.haven.core.data.preferences

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single item on the keyboard toolbar.
 *
 * [BuiltIn] references a [ToolbarKey] by ID — it gets special rendering
 * (icons for arrows, toggle state for modifiers, keyboard show/hide, etc.).
 *
 * [Custom] is a user-defined key with a display label and a string to send.
 * The [send] string is converted to UTF-8 bytes. Escape sequences use
 * standard JSON unicode escapes, e.g. `\u001b[A` for arrow-up.
 */
sealed class ToolbarItem {
    data class BuiltIn(val key: ToolbarKey) : ToolbarItem()
    data class Custom(val label: String, val send: String) : ToolbarItem()

    val displayLabel: String get() = when (this) {
        is BuiltIn -> key.label
        is Custom -> label
    }
}

/**
 * Complete toolbar layout: an ordered list of rows, each containing ordered items.
 *
 * JSON format:
 * ```json
 * [
 *   ["keyboard", "esc", "tab", "shift", "ctrl", "alt"],
 *   ["arrow_left", {"label": "|", "send": "|"}, {"label": "PgUp", "send": "\u001b[5~"}]
 * ]
 * ```
 *
 * - String elements reference built-in keys by ID (see [ToolbarKey.id]).
 * - Object elements define custom keys: `{"label": "...", "send": "..."}`.
 */
/** How the navigation keys (arrows, Home/End, PgUp/PgDn) are rendered on the toolbar. */
enum class NavBlockMode(val id: String, val label: String) {
    /** 2x4 aligned grid block (default). */
    ALIGNED("aligned", "Aligned block"),
    /** Inline with other keys — can be freely reordered. */
    INLINE("inline", "Inline keys");

    companion object {
        fun fromId(id: String): NavBlockMode? = entries.find { it.id == id }
    }
}

/**
 * Where the toolbar's fixed (non-draggable) controls sit in edit/reorder mode —
 * the done-✓, the desktop icon, add-key and settings. The draggable keys always
 * stay in the middle; this only decides which side(s) the fixed cluster occupies.
 * See `ReorderToolbarContent` in `core:toolbar`. (#224)
 */
enum class EditModeControlsPlacement(val id: String) {
    /** ✓/desktop on the left, add/settings on the right (the v5.59.29 behaviour). */
    SPLIT("split"),
    /** All fixed controls grouped on the left (default). */
    LEFT("left"),
    /** All fixed controls grouped on the right. */
    RIGHT("right");

    companion object {
        fun fromId(id: String): EditModeControlsPlacement? = entries.find { it.id == id }
    }
}

/**
 * Where the auto-shown "open desktop" (VNC/RDP) key sits on the toolbar — or
 * whether it shows at all. It's not a configurable [ToolbarKey]; it appears
 * automatically whenever the session can open a desktop, so this is the only
 * lever a user has over it. (#245)
 */
enum class DesktopKeyPlacement(val id: String) {
    /** Leading edge, stacked under the keyboard toggle (default, legacy behaviour). */
    LEFT("left"),
    /** Trailing edge, just before the add-key / reorder controls. */
    RIGHT("right"),
    /** Not shown on the toolbar at all. */
    HIDDEN("hidden");

    companion object {
        fun fromId(id: String): DesktopKeyPlacement? = entries.find { it.id == id }
    }
}

data class MacroPreset(val label: String, val send: String, val description: String)

val MACRO_PRESETS = listOf(
    MacroPreset("Paste", "PASTE", "Paste clipboard"),
    MacroPreset("^C", "\u0003", "Ctrl+C (interrupt)"),
    MacroPreset("^D", "\u0004", "Ctrl+D (EOF)"),
    MacroPreset("^Z", "\u001a", "Ctrl+Z (suspend)"),
    MacroPreset("^L", "\u000c", "Ctrl+L (clear)"),
    MacroPreset("^A", "\u0001", "Ctrl+A (tmux prefix)"),
    MacroPreset("^B", "\u0002", "Ctrl+B (tmux/screen prefix)"),
    MacroPreset("^R", "\u0012", "Ctrl+R (reverse search)"),
    MacroPreset("^W", "\u0017", "Ctrl+W (delete word)"),
    MacroPreset("^U", "\u0015", "Ctrl+U (delete line)"),
    MacroPreset("\u21e7Tab", "\u001b[Z", "Shift+Tab"),
    MacroPreset("C-A-Del", "\u001b[3;8~", "Ctrl+Alt+Delete"),
    MacroPreset("C-Del", "\u001b[3;6~", "Ctrl+Delete"),
    MacroPreset("C-Ins", "\u001b[2;5~", "Ctrl+Insert (copy)"),
    MacroPreset("S-Ins", "\u001b[2;2~", "Shift+Insert (paste)"),
)

data class ToolbarLayout(val rows: List<List<ToolbarItem>>) {

    val row1: List<ToolbarItem> get() = rows.getOrElse(0) { emptyList() }
    val row2: List<ToolbarItem> get() = rows.getOrElse(1) { emptyList() }

    fun toJson(): String {
        val arr = JSONArray()
        for (row in rows) {
            val rowArr = JSONArray()
            for (item in row) {
                when (item) {
                    is ToolbarItem.BuiltIn -> rowArr.put(item.key.id)
                    is ToolbarItem.Custom -> rowArr.put(JSONObject().apply {
                        put("label", item.label)
                        put("send", item.send)
                    })
                }
            }
            arr.put(rowArr)
        }
        return arr.toString(2)
    }

    companion object {
        val DEFAULT = ToolbarLayout(listOf(
            ToolbarKey.DEFAULT_ROW1.map { ToolbarItem.BuiltIn(it) },
            ToolbarKey.DEFAULT_ROW2.map { ToolbarItem.BuiltIn(it) },
        ))

        fun fromJson(json: String): ToolbarLayout {
            return try {
                val arr = JSONArray(json)
                val rows = (0 until arr.length()).map { i ->
                    val rowArr = arr.getJSONArray(i)
                    (0 until rowArr.length()).mapNotNull { j ->
                        when (val elem = rowArr.get(j)) {
                            is String -> ToolbarKey.fromId(elem)?.let { ToolbarItem.BuiltIn(it) }
                            is JSONObject -> {
                                val label = elem.optString("label", "")
                                val send = elem.optString("send", "")
                                if (label.isNotEmpty() && send.isNotEmpty()) {
                                    ToolbarItem.Custom(label, send)
                                } else null
                            }
                            else -> null
                        }
                    }
                }
                ToolbarLayout(rows)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        /**
         * Migrate from legacy comma-separated row1/row2 format.
         */
        fun fromLegacy(row1: String, row2: String): ToolbarLayout {
            return ToolbarLayout(listOf(
                ToolbarKey.fromIdString(row1).map { ToolbarItem.BuiltIn(it) },
                ToolbarKey.fromIdString(row2).map { ToolbarItem.BuiltIn(it) },
            ))
        }

        /**
         * Validate JSON and return an error message, or null if valid.
         */
        fun validate(json: String): String? {
            return try {
                val arr = JSONArray(json)
                if (arr.length() == 0) return "Layout must have at least one row"
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i)
                        ?: return "Row ${i + 1} is not an array"
                    for (j in 0 until row.length()) {
                        val elem = row.get(j)
                        when (elem) {
                            is String -> {
                                if (ToolbarKey.fromId(elem) == null) {
                                    return "Unknown key ID: \"$elem\""
                                }
                            }
                            is JSONObject -> {
                                if (!elem.has("label")) return "Custom key at row ${i + 1}, position ${j + 1} missing \"label\""
                                if (!elem.has("send")) return "Custom key at row ${i + 1}, position ${j + 1} missing \"send\""
                            }
                            else -> return "Invalid element at row ${i + 1}, position ${j + 1}"
                        }
                    }
                }
                null
            } catch (e: Exception) {
                "Invalid JSON: ${e.message}"
            }
        }
    }
}

/**
 * Snippets (custom send-key macros) can live in two places: as
 * [ToolbarItem.Custom] buttons placed on the toolbar rows, and in a separate
 * off-toolbar **library** (persisted under its own preference) that the
 * scissors sheet also lists. Setting a snippet to "Off" in toolbar settings
 * moves it to the library instead of deleting it (#244); only the trash
 * action removes it outright. These pure helpers are the single source of
 * truth for that placement logic, shared by the live toolbar, the settings
 * editor and the MCP snippet tools.
 */
object SnippetOps {
    /** Every snippet reachable from the scissors sheet: the ones placed on the
     *  toolbar plus the off-toolbar library, de-duplicated by label+send. */
    fun allSnippets(
        layout: ToolbarLayout,
        library: List<ToolbarItem.Custom>,
    ): List<ToolbarItem.Custom> =
        (layout.rows.flatMap { row -> row.filterIsInstance<ToolbarItem.Custom>() } + library)
            .distinct()

    /** Add a brand-new snippet to the library (no toolbar button). No-op if an
     *  identical snippet already exists anywhere. */
    fun addToLibrary(
        layout: ToolbarLayout,
        library: List<ToolbarItem.Custom>,
        item: ToolbarItem.Custom,
    ): List<ToolbarItem.Custom> =
        if (allSnippets(layout, library).contains(item)) library else library + item

    /** Remove a snippet everywhere — toolbar rows and library. */
    fun delete(
        layout: ToolbarLayout,
        library: List<ToolbarItem.Custom>,
        item: ToolbarItem.Custom,
    ): Pair<ToolbarLayout, List<ToolbarItem.Custom>> =
        ToolbarLayout(layout.rows.map { row -> row.filterNot { it == item } }) to
            library.filterNot { it == item }

    /**
     * Move a snippet to [row] (0 = row 1, 1 = row 2, null = off-toolbar
     * library). Strips it from wherever it currently sits first, then places
     * it. A target row that doesn't exist yet is created empty.
     */
    fun place(
        layout: ToolbarLayout,
        library: List<ToolbarItem.Custom>,
        item: ToolbarItem.Custom,
        row: Int?,
    ): Pair<ToolbarLayout, List<ToolbarItem.Custom>> {
        val strippedRows = layout.rows.map { r -> r.filterNot { it == item } }
        val strippedLib = library.filterNot { it == item }
        if (row == null) {
            return ToolbarLayout(strippedRows) to (strippedLib + item)
        }
        val rows = strippedRows.toMutableList()
        val target = row.coerceAtLeast(0)
        while (rows.size <= target) rows.add(emptyList())
        rows[target] = rows[target] + item
        return ToolbarLayout(rows) to strippedLib
    }

    /** Serialise the library to JSON (`[{"label":..,"send":..}, …]`). */
    fun libraryToJson(items: List<ToolbarItem.Custom>): String {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply { put("label", it.label); put("send", it.send) })
        }
        return arr.toString()
    }

    /** Parse the library from JSON; malformed/empty entries are skipped. */
    fun libraryFromJson(json: String): List<ToolbarItem.Custom> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("label", "")
                val send = o.optString("send", "")
                if (label.isNotEmpty() && send.isNotEmpty()) {
                    ToolbarItem.Custom(label, send)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Rebuild the toolbar rows from the settings editor's per-key row assignments.
 *
 * The editor models keys as ROW1 / ROW2 / OFF sets, which on its own loses the
 * on-toolbar order — so the old Save path rewrote every row in enum/list order,
 * wiping any hold-and-drag arrangement the moment you toggled an unrelated key
 * (#245). This preserves the existing order: a key that stays in its row keeps
 * its position; only keys newly moved into a row are appended (built-ins in
 * [ToolbarKey] order, customs in editor order).
 */
object ToolbarEditorOps {
    /**
     * @param previous the currently-saved layout (the source of existing order)
     * @param builtinRows ToolbarKey → target row (0 = row 1, 1 = row 2, null = off)
     * @param customRows ordered (custom item → target row) from the editor
     * @return the two rebuilt rows [row1, row2]
     */
    fun rebuildRows(
        previous: ToolbarLayout,
        builtinRows: Map<ToolbarKey, Int?>,
        customRows: List<Pair<ToolbarItem.Custom, Int?>>,
    ): List<List<ToolbarItem>> {
        fun rowFor(target: Int): List<ToolbarItem> {
            val existing = previous.rows.getOrElse(target) { emptyList() }
            // 1. items already in this row that are still assigned here — kept in order.
            val kept = existing.filter { item ->
                when (item) {
                    is ToolbarItem.BuiltIn -> builtinRows[item.key] == target
                    is ToolbarItem.Custom -> customRows.any { it.first == item && it.second == target }
                }
            }
            val keptBuiltins = kept.filterIsInstance<ToolbarItem.BuiltIn>().map { it.key }.toSet()
            val keptCustoms = kept.filterIsInstance<ToolbarItem.Custom>().toSet()
            // 2. built-ins newly moved into this row, appended in enum order.
            val addedBuiltins = ToolbarKey.entries
                .filter { builtinRows[it] == target && it !in keptBuiltins }
                .map { ToolbarItem.BuiltIn(it) }
            // 3. customs newly moved into this row, appended in editor order.
            val addedCustoms = customRows
                .filter { it.second == target && it.first !in keptCustoms }
                .map { it.first }
            return kept + addedBuiltins + addedCustoms
        }
        return listOf(rowFor(0), rowFor(1))
    }
}
