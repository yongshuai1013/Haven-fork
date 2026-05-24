package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** org.json is an Android stub in plain JUnit; Robolectric provides a real impl. */
@RunWith(RobolectricTestRunner::class)
class AppWindowDefListTest {

    @Test
    fun jsonRoundTripPreservesAllFields() {
        val list = AppWindowDefList(
            listOf(
                AppWindowDef(
                    id = "id-1",
                    label = "Image viewer",
                    command = "imv /root/board.png",
                    createdBy = AppWindowOrigin.USER,
                    lastUsed = 1000L,
                ),
                AppWindowDef(
                    id = "id-2",
                    label = "Player",
                    command = "mpv /root/clip.mp4",
                    createdBy = AppWindowOrigin.AGENT,
                    lastUsed = 2000L,
                ),
            ),
        )
        val restored = AppWindowDefList.fromJson(list.toJson())
        assertEquals(list.items, restored.items)
    }

    @Test
    fun fromJsonOfEmptyOrGarbageIsEmpty() {
        assertTrue(AppWindowDefList.fromJson("").items.isEmpty())
        assertTrue(AppWindowDefList.fromJson("not json").items.isEmpty())
        assertEquals(0, AppWindowDefList.fromJson("[]").items.size)
    }

    @Test
    fun fromJsonDropsEntriesWithNoCommandAndDefaultsLabelToCommand() {
        // One valid entry (no label → label defaults to command), one invalid
        // (no command → dropped).
        val json = """[{"command":"feh /x.png"},{"label":"orphan"}]"""
        val list = AppWindowDefList.fromJson(json)
        assertEquals(1, list.items.size)
        assertEquals("feh /x.png", list.items[0].command)
        assertEquals("feh /x.png", list.items[0].label)
    }

    @Test
    fun unknownOriginFallsBackToUser() {
        val json = """[{"command":"imv x","createdBy":"martian"}]"""
        assertEquals(AppWindowOrigin.USER, AppWindowDefList.fromJson(json).items[0].createdBy)
    }
}
