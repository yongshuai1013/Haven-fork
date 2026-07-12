package sh.haven.feature.terminal.agent

import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.feature.terminal.OscHandler

/**
 * Registry semantics behind the #378 fix: open_local_shell claims the slot
 * atomically via [TerminalSessionRegistry.registerHeadless], and when a UI
 * tab wins (or the claim raced a tab build), syncSessions repoints the entry
 * at the tab's live handles via [TerminalSessionRegistry.adoptTabHandles].
 */
class TerminalSessionRegistryTest {

    private val registry = TerminalSessionRegistry()
    private val feedA: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }
    private val feedB: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }

    private fun registerHeadless(
        sessionId: String,
        emulator: TerminalEmulator,
        feed: (ByteArray, Int, Int) -> Unit = feedA,
    ) = registry.registerHeadless(
        sessionId,
        emulator,
        feed,
        mouseMode = MutableStateFlow(false),
        activeMouseMode = MutableStateFlow<Int?>(null),
        bracketPasteMode = MutableStateFlow(false),
    )

    @Test
    fun `registerHeadless claims an empty slot with feed and mode flows in one write`() {
        val emulator = mockk<TerminalEmulator>()

        assertTrue(registerHeadless("s1", emulator))

        val entry = registry.get("s1")!!
        assertSame(emulator, entry.emulator)
        assertSame(feedA, entry.feedOutput)
        assertFalse(entry.mouseMode!!.value)
        // No OSC handler on a headless claim — a later same-emulator tab
        // adoption must still attach the full tab handles (#336).
        assertNull(entry.oscHandler)
    }

    @Test
    fun `registerHeadless loses to an existing tab entry and leaves it untouched`() {
        val tabEmulator = mockk<TerminalEmulator>()
        registry.register("s1", tabEmulator)

        assertFalse(registerHeadless("s1", mockk<TerminalEmulator>()))

        assertSame(tabEmulator, registry.get("s1")!!.emulator)
        assertNull(registry.get("s1")!!.feedOutput)
    }

    @Test
    fun `adoptTabHandles repoints emulator feed and osc at the tab`() {
        val headless = mockk<TerminalEmulator>()
        val tabEmulator = mockk<TerminalEmulator>()
        val tabOsc = OscHandler()
        assertTrue(registerHeadless("s1", headless, feedA))

        registry.adoptTabHandles(
            "s1",
            tabEmulator,
            mouseMode = MutableStateFlow(true),
            activeMouseMode = MutableStateFlow<Int?>(1002),
            bracketPasteMode = MutableStateFlow(true),
            oscHandler = tabOsc,
            feedOutput = feedB,
        )

        val entry = registry.get("s1")!!
        assertSame(tabEmulator, entry.emulator)
        assertSame(feedB, entry.feedOutput)
        assertSame(tabOsc, entry.oscHandler)
        assertTrue(entry.mouseMode!!.value)
        assertEquals(1002, entry.activeMouseMode!!.value)
    }

    @Test
    fun `adoptTabHandles preserves controllers set by the tab's composition`() {
        val selection = mockk<SelectionController>()
        val scroll = mockk<ScrollController>()
        val compose = mockk<ComposeController>()
        assertTrue(registerHeadless("s1", mockk<TerminalEmulator>()))
        registry.setSelectionController("s1", selection)
        registry.setScrollController("s1", scroll)
        registry.setComposeController("s1", compose)

        registry.adoptTabHandles(
            "s1",
            mockk<TerminalEmulator>(),
            mouseMode = MutableStateFlow(false),
            activeMouseMode = MutableStateFlow<Int?>(null),
            bracketPasteMode = MutableStateFlow(false),
            oscHandler = OscHandler(),
            feedOutput = feedB,
        )

        val entry = registry.get("s1")!!
        assertSame(selection, entry.selectionController)
        assertSame(scroll, entry.scrollController)
        assertSame(compose, entry.composeController)
    }

    @Test
    fun `adoptTabHandles is a no-op for an unregistered session`() {
        registry.adoptTabHandles(
            "ghost",
            mockk<TerminalEmulator>(),
            mouseMode = MutableStateFlow(false),
            activeMouseMode = MutableStateFlow<Int?>(null),
            bracketPasteMode = MutableStateFlow(false),
            oscHandler = OscHandler(),
            feedOutput = feedA,
        )
        assertNull(registry.get("ghost"))
    }
}
