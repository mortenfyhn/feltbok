package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The core add-observation loop, driven through the real screens on the minified releaseTest
 * variant: search a species -> save -> it shows in the list. This is the kind of plumbing
 * regression unit tests can't catch (a broken nav step, a wrongly-disabled Save), and - running on
 * the shrunk variant - it doubles as R8 cover for the Compose UI path (#142).
 *
 * Saving needs a locality, which normally comes from GPS or the locality-picker map. Neither drives
 * reliably in a test (no fix; the map is pixel/projection-bound), so we seed one through the
 * picker's own public entry point [MainViewModel.createNewLocality] - the single non-UI step.
 */
@RunWith(AndroidJUnit4::class)
class AddObservationFlowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun addObservationAppearsInList() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        rule.setContent { App(vm) }

        // List -> the + button opens species search.
        rule.onNodeWithText("+").performClick()

        // Type a prefix (not the full name) so the field's own text stays distinct from the result
        // row's "kjøttmeis" - otherwise onNodeWithText would match both. Results are debounced.
        rule.onNode(hasSetTextAction()).performTextInput("kjøttmei")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("kjøttmeis").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("kjøttmeis").performClick()

        // Detail screen: Save is disabled without a locality. Seed one (see class note).
        rule.runOnUiThread { vm.createNewLocality(63.43, 10.39, 100, "Testlokalitet") }

        // Save returns to the list, where NoteRow renders "<count> <species>".
        rule.onNodeWithText(Strings.Detail.save).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }
}
