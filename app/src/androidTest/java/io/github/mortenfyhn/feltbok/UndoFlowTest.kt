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
 * The undo offer (#122) as it's actually wired to the UI. `UndoOfferTest` covers the offer/clear
 * state machine on the JVM; what it can't reach is whether the snackbar App() hosts really appears
 * after a delete, whether "Angre" puts the note back, and whether navigating away dismisses it —
 * the snackbar is hosted above the screen switch in App(), so those are wiring, not logic.
 *
 * Selection mode is entered through [MainViewModel.setSelection] rather than a long-press: marking
 * is driven by the list's own drag-to-select pointerInput, which a synthetic long-press fights.
 * The actions under test (the bar's Slett, the snackbar's Angre) are driven through the UI.
 */
@RunWith(AndroidJUnit4::class)
class UndoFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun start(): MainViewModel {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { App(vm) }
        return vm
    }

    @Test
    fun undoRestoresADeletedNote() {
        val vm = start()
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.setSelection(vm.notes.map { it.id })
        }

        // One mark deletes straight away - the snackbar is the safety net, so no confirm dialog.
        rule.onNodeWithText(Strings.Notes.deleteSelected).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertDoesNotExist()

        rule.onNodeWithText(Strings.Notes.deleted(1)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }

    @Test
    fun navigatingAwayDismissesTheUndoOffer() {
        val vm = start()
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.setSelection(vm.notes.map { it.id })
        }
        rule.onNodeWithText(Strings.Notes.deleteSelected).performClick()
        rule.onNodeWithText(Strings.Notes.deleted(1)).assertIsDisplayed()

        // "+" leaves the list, which the dismiss effect in App() treats as acting on the offer.
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithText(Strings.Notes.undo).assertDoesNotExist()
    }
}
