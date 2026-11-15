package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    @IgnorableReturnValue
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
    fun swipingLeftDeletesANoteAndUndoBringsItBack() {
        val vm = start()
        // A second note keeps the day group alive, so the row comes back into a list that kept
        // its saved state - the case where the swipe state used to come back dismissed and
        // delete the note again.
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.seedNote("blåmeis")
        }
        val row = rule.onNodeWithText("kjøttmeis", substring = true)

        // Only leftward deletes.
        row.performTouchInput { swipeRight() }
        row.assertIsDisplayed()

        row.performTouchInput { swipeLeft() }
        rule.onNodeWithText("kjøttmeis", substring = true).assertDoesNotExist()
        rule.onNodeWithText(Strings.Notes.deleted(1)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }

    /**
     * The list clears its flash 5 s after it starts; a row whose fade began late (here: the same
     * delete undone twice, the second time 3 s in) used to be cut off mid-fade and stay tinted.
     */
    @Test
    fun aFlashCutShortStillFadesOut() {
        val vm = start()
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.seedNote("blåmeis")
        }
        val id = vm.notes.first { it.species == "kjøttmeis" }.id
        rule.mainClock.autoAdvance = false
        repeat(2) {
            rule.runOnUiThread { vm.deleteNote(id) }
            rule.mainClock.advanceTimeByFrame()   // let the row leave, so the undo composes it afresh
            rule.runOnUiThread { vm.undo() }
            rule.mainClock.advanceTimeBy(3_000)
        }
        rule.mainClock.advanceTimeBy(10_000)

        // The top-left corner of each name is background, not glyph: the flashed row must match the other.
        fun corner(name: String) = rule.onNodeWithText(name, substring = true).captureToImage().toPixelMap()[0, 0]
        assertEquals(corner("blåmeis"), corner("kjøttmeis"))
    }

    /** While marking, a sideways drag must not delete one row out from under the selection. */
    @Test
    fun swipingDoesNothingWhileMarking() {
        val vm = start()
        rule.runOnUiThread {
            vm.seedNote("kjøttmeis")
            vm.setSelection(vm.notes.map { it.id })
        }
        rule.onNodeWithText("kjøttmeis", substring = true).performTouchInput { swipeLeft() }
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }

    /**
     * The other Undoable branch: a discarded draft. Different shape from the delete case: undo
     * doesn't re-add a note, it returns to DETAIL with the draft still sitting in the editor.
     */
    @Test
    fun undoAfterDiscardingADraftReturnsToTheEditor() {
        start()
        rule.onNodeWithText("+").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("kjøttmei")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("kjøttmeis").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("kjøttmeis").performClick()

        // The editor's ✕ discards the draft (with changes, so the undo path, not plain cancel).
        rule.onNodeWithText("✕").performClick()
        rule.onNodeWithText(Strings.Detail.discarded(wasEdit = false)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()

        // Back in the editor, draft intact - not the list, and no note was saved.
        rule.onNodeWithText(Strings.Detail.save).assertIsDisplayed()
    }

    /** The Edited branch (09fec4e): undo after *saving* an edit restores the original values. */
    @Test
    fun undoAfterASavedEditRestoresTheOriginal() {
        val vm = start()
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }
        rule.onNodeWithText("kjøttmeis", substring = true).performClick()

        // Change the count the same way the batch test does (fields set through the ViewModel).
        rule.runOnUiThread { vm.setCount(5) }
        rule.onNodeWithText(Strings.Detail.save).performClick()
        rule.onNodeWithText(Strings.Notes.edited(1)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()

        // The original note is back - not the edited count of 5.
        rule.onNodeWithText("1 kjøttmeis").assertIsDisplayed()
        rule.onNodeWithText("5 kjøttmeis").assertDoesNotExist()
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

        // "+" leaves the list, which the dismiss effect in App() treats as acting on the offer. The
        // snackbar floats above the button, so the tap reaches it (it used to land on "Angre").
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithText(Strings.Notes.undo).assertDoesNotExist()
    }
}
