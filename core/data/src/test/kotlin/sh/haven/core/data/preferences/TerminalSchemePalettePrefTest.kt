package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #407 — the ANSI-palette override must default OFF.
 *
 * v5.51.0 (#165) started pushing each scheme's 16-colour ANSI palette to the
 * emulator, which remaps the colours full-screen TUIs like mutt rely on (ANSI
 * white → a scheme "cream"), reading as a regression from v5.50.0. The fix
 * makes that override opt-in: default false keeps libvterm's stock palette, so
 * on upgrade nobody's terminal colours change. This locks the default and the
 * set/get round-trip that the toggle depends on.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalSchemePalettePrefTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun defaultsOffSoUpgradeKeepsStockAnsiPalette() = runBlocking {
        assertFalse(
            "scheme ANSI palette must not be applied by default (#407)",
            repo().terminalApplySchemePalette.first(),
        )
    }

    @Test
    fun toggleRoundTrips() = runBlocking {
        val repo = repo()
        repo.setTerminalApplySchemePalette(true)
        assertTrue(repo.terminalApplySchemePalette.first())
        repo.setTerminalApplySchemePalette(false)
        assertFalse(repo.terminalApplySchemePalette.first())
    }
}
