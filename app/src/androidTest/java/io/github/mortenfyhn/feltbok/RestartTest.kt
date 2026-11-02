package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What you saved is still there after a restart: a second view model loads notes, the archive and
 * the use scores from disk, exactly as the app does on launch. The unit tests cover the file
 * formats; this covers the device's real storage path (AndroidStorage's atomic write and the
 * archive's append) in the minified release build, where an R8 strip would only show at runtime.
 */
@RunWith(AndroidJUnit4::class)
class RestartTest {
    @get:Rule
    val clearData = ClearAppDataRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test
    fun notesArchiveAndUseScoresSurviveARestart() {
        lateinit var kept: List<Note>
        lateinit var archived: Note
        onMain {
            val vm = MainViewModel(app)
            vm.seedNote("kjøttmeis")
            vm.seedNote("gråmåke")
            vm.seedNote("stokkand")
            archived = vm.notes.first { it.species == "gråmåke" }
            vm.setSelection(listOf(archived.id))
            vm.deleteSelected()
            kept = vm.notes.toList()
        }
        onMain {
            val vm = MainViewModel(app)
            assertEquals(kept, vm.notes.toList())
            vm.openArchive()
            assertEquals(listOf(archived), vm.archived.toList())
            assertTrue("kjøttmeis keeps its use score", vm.useScore("Parus major") > 0.0)
        }
    }
}
