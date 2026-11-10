package io.github.mortenfyhn.feltbok

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The co-observer picker's two modes (#191): tapping a name flips its switch, and long-press enters
 * a delete mode (as when marking notes, dragging sweeps a range) where Slett forgets the marked
 * names, with the undo snackbar as the safety net.
 * `ForgetCoObsTest` covers forget/undo on the JVM; this covers the gestures, the header swap, Back,
 * and that the snackbar shows on the picker (App() dismisses it on other screens than the list).
 */
@RunWith(AndroidJUnit4::class)
class CoObserverFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Opens the picker from the list's party strip, with Kari and Ola as known names. */
    private fun openPicker() {
        val vm = MainViewModel(rule.activity.application)
        rule.setContent { App(vm) }
        rule.runOnUiThread { listOf("Kari", "Ola").forEach(vm::addCoObs) }
        rule.onNodeWithText(Strings.CoObs.none).performClick()
    }

    @Test
    fun tappingANameSwitchesItOnAndSetsTheParty() {
        openPicker()
        rule.onNodeWithText("Kari").assertIsOff().performClick()
        rule.onNodeWithText("Kari").assertIsOn()
        rule.onNodeWithText(Strings.CoObs.titleCount(1)).assertIsDisplayed()
        rule.onNodeWithText(Strings.CoObs.done).performClick()
        rule.onNodeWithText("Kari").assertIsDisplayed()   // the list strip's names
    }

    @Test
    fun slaAvAlleSwitchesEveryoneOff() {
        openPicker()
        // Greyed out, not hidden, with nobody on, so the list doesn't jump.
        rule.onNodeWithText(Strings.CoObs.allOff).assertIsNotEnabled()
        rule.onNodeWithText("Kari").performClick()
        rule.onNodeWithText("Ola").performClick()

        rule.onNodeWithText(Strings.CoObs.allOff).assertIsEnabled().performClick()
        rule.onNodeWithText("Kari").assertIsOff()
        rule.onNodeWithText("Ola").assertIsOff()
        rule.onNodeWithText(Strings.CoObs.allOff).assertIsNotEnabled()
    }

    @Test
    fun longPressMarksNamesAndSlettDeletesThemUndoably() {
        openPicker()
        rule.onNodeWithText("Kari").performTouchInput { longClick() }
        rule.onNodeWithText(Strings.Notes.selected(1)).assertIsDisplayed()
        rule.onNodeWithText("Kari").assertIsSelected()
        rule.onNodeWithText("Ola").performClick()
        rule.onNodeWithText(Strings.Notes.selected(2)).assertIsDisplayed()

        // No confirm dialog: the names go at once, and the picker leaves delete mode.
        rule.onNodeWithText(Strings.CoObs.remove).performClick()
        rule.onNodeWithText("Kari").assertDoesNotExist()
        rule.onNodeWithText("Ola").assertDoesNotExist()
        rule.onNodeWithText(Strings.CoObs.title).assertIsDisplayed()

        rule.onNodeWithText(Strings.CoObs.forgot(2)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()
        rule.onNodeWithText("Kari").assertIsDisplayed()
        rule.onNodeWithText("Ola").assertIsDisplayed()
    }

    @Test
    fun longPressAndDragMarksARange() {
        openPicker()
        val from = rule.onNodeWithText("Kari").fetchSemanticsNode().boundsInRoot.center
        val to = rule.onNodeWithText("Ola").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(from.x, (from.y + to.y) / 2))
            moveTo(to)
            up()
        }
        rule.onNodeWithText(Strings.Notes.selected(2)).assertIsDisplayed()
        rule.onNodeWithText("Kari").assertIsSelected()
        rule.onNodeWithText("Ola").assertIsSelected()
    }

    @Test
    fun backLeavesDeleteModeWithoutDeleting() {
        openPicker()
        rule.onNodeWithText("Kari").performTouchInput { longClick() }
        rule.onNodeWithText(Strings.Notes.selected(1)).assertIsDisplayed()

        // runOnIdle so the picker's BackHandler is enabled before the back is sent (see NavigationFlowTest).
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText(Strings.CoObs.title).assertIsDisplayed()
        rule.onNodeWithText("Kari").assertIsOff()
    }
}
