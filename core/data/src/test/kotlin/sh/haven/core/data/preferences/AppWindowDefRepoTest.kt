package sh.haven.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repo-level behaviour of the saved app-window CRUD against a real DataStore.
 * The key distinction: [UserPreferencesRepository.upsertAppWindowDef] matches
 * by command (so it can't change one), while [updateAppWindowDef] targets the
 * id and can rewrite both fields.
 */
@RunWith(RobolectricTestRunner::class)
class AppWindowDefRepoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun repo(): UserPreferencesRepository {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            tempFolder.newFile("prefs_${System.nanoTime()}.preferences_pb")
        }
        return UserPreferencesRepository(org.robolectric.RuntimeEnvironment.getApplication(), ds)
    }

    @Test
    fun updateByIdRewritesLabelAndCommand() = runBlocking {
        val repo = repo()
        repo.upsertAppWindowDef("Viewer", "imv /root/a.png", AppWindowOrigin.USER)
        val def = repo.appWindowDefs.first().items.single()

        repo.updateAppWindowDef(def.id, "Editor", "nano /root/notes.txt")

        val updated = repo.appWindowDefs.first().items.single()
        assertEquals(def.id, updated.id)
        assertEquals("Editor", updated.label)
        assertEquals("nano /root/notes.txt", updated.command)
        // Origin preserved across the edit.
        assertEquals(AppWindowOrigin.USER, updated.createdBy)
    }

    @Test
    fun upsertByCommandCannotChangeCommandButUpdateByIdCan() = runBlocking {
        val repo = repo()
        repo.upsertAppWindowDef("Viewer", "imv /root/a.png", AppWindowOrigin.USER)
        val def = repo.appWindowDefs.first().items.single()

        // Upsert with a *different* command just adds a second entry.
        repo.upsertAppWindowDef("Viewer", "imv /root/b.png", AppWindowOrigin.USER)
        assertEquals(2, repo.appWindowDefs.first().items.size)

        // Update-by-id changes the first entry's command in place — still 2.
        repo.updateAppWindowDef(def.id, "Viewer", "imv /root/c.png")
        val items = repo.appWindowDefs.first().items
        assertEquals(2, items.size)
        assertEquals("imv /root/c.png", items.single { it.id == def.id }.command)
    }
}
