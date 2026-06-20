package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards #238 key reordering: saved-order application + one-step moves. */
class KeyOrderingTest {

    private fun ids(items: List<String>) = items

    @Test
    fun `empty order leaves items untouched`() {
        val items = listOf("a", "b", "c")
        assertEquals(items, KeyOrdering.applyOrder(items, emptyList()) { it })
    }

    @Test
    fun `items are sorted by saved order`() {
        val items = listOf("a", "b", "c") // backing order (createdAt DESC)
        val order = listOf("c", "a", "b")
        assertEquals(listOf("c", "a", "b"), KeyOrdering.applyOrder(items, order) { it })
    }

    @Test
    fun `ids absent from the order keep backing order and sort after`() {
        val items = listOf("a", "b", "c", "d")
        val order = listOf("c", "a") // b, d unranked
        assertEquals(listOf("c", "a", "b", "d"), KeyOrdering.applyOrder(items, order) { it })
    }

    @Test
    fun `stale ids in the order are ignored`() {
        val items = listOf("a", "b")
        val order = listOf("gone", "b", "a")
        assertEquals(listOf("b", "a"), KeyOrdering.applyOrder(items, order) { it })
    }

    @Test
    fun `move up swaps with previous`() {
        assertEquals(listOf("b", "a", "c"), KeyOrdering.move(listOf("a", "b", "c"), "b", up = true))
    }

    @Test
    fun `move down swaps with next`() {
        assertEquals(listOf("a", "c", "b"), KeyOrdering.move(listOf("a", "b", "c"), "b", up = false))
    }

    @Test
    fun `move at the edge is a no-op`() {
        assertEquals(listOf("a", "b", "c"), KeyOrdering.move(listOf("a", "b", "c"), "a", up = true))
        assertEquals(listOf("a", "b", "c"), KeyOrdering.move(listOf("a", "b", "c"), "c", up = false))
    }

    @Test
    fun `move of an unknown id is a no-op`() {
        assertEquals(listOf("a", "b"), KeyOrdering.move(listOf("a", "b"), "x", up = true))
    }
}
