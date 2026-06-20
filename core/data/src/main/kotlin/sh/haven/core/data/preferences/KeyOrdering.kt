package sh.haven.core.data.preferences

/**
 * Pure helpers for user-defined SSH-key ordering (#238). The order is persisted
 * as a list of key IDs in preferences (migration-free, mirroring `screenOrder`);
 * keys absent from that list keep their backing order (createdAt DESC from the
 * DAO) and sort after the explicitly-ordered ones.
 */
object KeyOrdering {

    /**
     * Sort [items] by the saved [order] of IDs. Items whose ID is in [order]
     * come first in that order; the rest keep their original relative position
     * (the sort is stable), appended after.
     */
    fun <T> applyOrder(items: List<T>, order: List<String>, id: (T) -> String): List<T> {
        if (order.isEmpty()) return items
        val rank = order.withIndex().associate { (i, v) -> v to i }
        return items.sortedBy { rank[id(it)] ?: Int.MAX_VALUE }
    }

    /**
     * Move [keyId] one step toward the front ([up]) or back within
     * [currentOrder] — the full displayed ID order. Returns the new full ID
     * list to persist, or [currentOrder] unchanged if the move isn't possible
     * (key absent, or already at the edge).
     */
    fun move(currentOrder: List<String>, keyId: String, up: Boolean): List<String> {
        val i = currentOrder.indexOf(keyId)
        if (i < 0) return currentOrder
        val j = if (up) i - 1 else i + 1
        if (j !in currentOrder.indices) return currentOrder
        return currentOrder.toMutableList().apply {
            val tmp = this[i]; this[i] = this[j]; this[j] = tmp
        }
    }
}
