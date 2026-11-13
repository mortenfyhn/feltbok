package io.github.mortenfyhn.feltbok

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bottom buttons hide while the keyboard is open, so they don't ride up above the keys.
 *
 * Needs a soft keyboard on the device. The activity is made edge-to-edge as `MainActivity` is:
 * otherwise the window absorbs the keyboard and Compose never sees its height.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun start(): MainViewModel {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        val vm = MainViewModel(rule.activity.application)
        rule.setContent { App(vm) }
        return vm
    }

    private fun waitFor(text: String, shown: Boolean) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() == shown }

    @Test
    fun editorHidesSlettAndLagreWhileTypingTheCount() {
        val vm = start()
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }
        rule.onNodeWithText("kjøttmeis", substring = true).performClick()

        // The count is the editor's only text field; the comments open in a dialog.
        rule.onNode(hasSetTextAction()).performClick()
        waitFor(Strings.Detail.delete, shown = false)
        rule.onNodeWithText(Strings.Detail.save).assertDoesNotExist()

        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor(Strings.Detail.delete, shown = true)
    }

    @Test
    fun coObserverPickerHidesSlaAvAlleWhileTyping() {
        val vm = start()
        rule.runOnUiThread { vm.addCoObs("Kari") }
        rule.onNodeWithText(Strings.CoObs.none).performClick()
        rule.onNodeWithText(Strings.CoObs.allOff).assertExists()

        rule.onNode(hasSetTextAction()).performClick()
        waitFor(Strings.CoObs.allOff, shown = false)
    }
}
