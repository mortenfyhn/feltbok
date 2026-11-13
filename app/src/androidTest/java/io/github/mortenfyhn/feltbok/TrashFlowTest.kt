package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The papirkurv (trash bin, `archive` in the code) as wired to the UI: the footer link that opens
 * it, and the delete -> papirkurv -> Gjenopprett round trip. Both are composition, not view-model
 * logic, so no JVM test reaches them. Marking on the list is entered through
 * [MainViewModel.setSelection], as in [UndoFlowTest].
 */
@RunWith(AndroidJUnit4::class)
class TrashFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun start(): MainViewModel {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { App(vm) }
        return vm
    }

    /** #192: after a long drag-select the thumb rests on the footer, so a tap there opened the papirkurv. */
    @Test
    fun footerIsHiddenWhileMarking() {
        val vm = start()
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }
        rule.onNodeWithText(Strings.Archive.title).assertIsDisplayed()

        rule.runOnUiThread { vm.setSelection(vm.notes.map { it.id }) }
        rule.onNodeWithText(Strings.Archive.title).assertDoesNotExist()
        rule.onNodeWithText(Strings.Notes.feedback).assertDoesNotExist()

        rule.runOnUiThread { vm.clearSelection() }
        rule.onNodeWithText(Strings.Archive.title).assertIsDisplayed()
    }

    @Test
    fun deletedNoteCanBeRestoredFromThePapirkurv() {
        val vm = start()
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.setSelection(vm.notes.map { it.id })
        }
        rule.onNodeWithText(Strings.Notes.deleteSelected).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertDoesNotExist()

        // Leaving marking brought the footer back, so its link opens the papirkurv with the note in it.
        rule.onNodeWithText(Strings.Archive.title).performClick()
        rule.onNodeWithText(Strings.Archive.empty).assertDoesNotExist()

        // A plain tap marks a deleted note; Gjenopprett moves it back and returns to the list.
        rule.onNodeWithText("kjøttmeis", substring = true).performClick()
        rule.onNodeWithText(Strings.Archive.restore).performClick()
        rule.onNodeWithText(Strings.Archive.restore).assertDoesNotExist()
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }
}
