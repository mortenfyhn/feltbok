package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Saving a new spot closes its panel and leaves you on the picker map, to see it and tap it. The
 *  highlight and the tap are drawn on the osmdroid map, so they stay on the manual checklist. */
@RunWith(AndroidJUnit4::class)
class NewLocalityFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun savingANewSpotStaysOnThePicker() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { App(vm) }
        rule.runOnUiThread { vm.openCurrentLocalityPicker() }

        rule.onNodeWithText(Strings.Picker.newButton).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Brygga")
        rule.onNodeWithText(Strings.Picker.save).performClick()

        rule.onNodeWithText(Strings.Picker.titlePick).assertIsDisplayed()
        rule.onNodeWithText(Strings.Picker.save).assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(Screen.LOCALITY, vm.screen)
            assertTrue(vm.localities.any { it.newLoc && it.lokalitet == "Brygga" })
        }
    }
}
