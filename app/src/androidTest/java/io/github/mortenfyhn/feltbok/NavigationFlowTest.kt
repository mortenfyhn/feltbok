package io.github.mortenfyhn.feltbok

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Navigation wiring: that system Back stays inside the app, and that the list keeps its scroll
 * position across a trip into the editor (#146 — a `LazyListState` remembered inside ListScreen is
 * discarded when that screen leaves composition, so the fix hoists it above the screen switch).
 * Neither is reachable from a pure-JVM test: both are about what survives recomposition.
 *
 * Uses `createAndroidComposeRule` rather than `createComposeRule` purely to get at
 * `rule.activity.onBackPressedDispatcher` — the bare host activity gives no handle on it, and
 * Espresso's `pressBack()` would mean adding espresso-core for one call.
 */
@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun start(): MainViewModel {
        val vm = MainViewModel(rule.activity.application)
        rule.setContent { App(vm) }
        return vm
    }

    /**
     * `runOnIdle`, not `runOnUiThread`: App()'s BackHandler is `enabled = showExport || screen !=
     * LIST`, so dispatching back before the pending recomposition applies the new screen finds the
     * handler still disabled — and an unconsumed back finishes the activity, taking the whole
     * compose hierarchy with it.
     */
    private fun pressBack() = rule.runOnIdle {
        rule.activity.onBackPressedDispatcher.onBackPressed()
    }

    @Test
    fun backLeavesSearchAndExportWithoutExitingTheApp() {
        val vm = start()
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }

        // "+" opens species search; Back must return to the list, not close the app.
        rule.onNodeWithText("+").performClick()
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
        pressBack()
        rule.onNodeWithText("+").assertIsDisplayed()

        // The export overlay is a separate Back branch in App()'s handler.
        rule.onNodeWithText(Strings.Notes.export).performClick()
        rule.onNodeWithText(Strings.Export.copy).assertIsDisplayed()
        pressBack()
        rule.onNodeWithText(Strings.Export.copy).assertDoesNotExist()
        rule.onNodeWithText("+").assertIsDisplayed()
    }

    @Test
    fun listKeepsScrollPositionAcrossTheEditor() {
        val vm = start()
        // Enough notes that the top and bottom of the list can't be on screen together.
        val names = vm.species.map { it.norsk }.filter { it.isNotBlank() }.distinct().take(30)
        rule.runOnUiThread { names.forEach { vm.seedNote(it) } }

        // Newest note sits at the top of the list, so the first one seeded is at the bottom.
        val bottom = names.first()
        val top = names.last()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(bottom, substring = true))
        rule.onNodeWithText(bottom, substring = true).assertIsDisplayed()
        rule.onNodeWithText(top, substring = true).assertDoesNotExist()

        // Open that note and come straight back out - an untouched editor just cancels.
        rule.onNodeWithText(bottom, substring = true).performClick()
        rule.onNodeWithText(Strings.Detail.save).assertIsDisplayed()
        pressBack()

        // Still scrolled where we left it, with no scrolling of our own in between.
        rule.onNodeWithText(bottom, substring = true).assertIsDisplayed()
        rule.onNodeWithText(top, substring = true).assertDoesNotExist()
    }

    /**
     * The remaining screens the app-level Back handler (or the map picker's own) must catch:
     * Synk and settings from the list, then the editor's two subscreens, the locality picker and
     * the co-observer picker. A back that falls through finishes the activity, so every assertIsDisplayed
     * below doubles as "the app is still alive".
     */
    @Test
    fun backReturnsFromSyncSettingsAndTheEditorsSubscreens() {
        val vm = start()

        // Synk: the footer link opens the WebView screen; Back must close it, not the app.
        // The screen starts on a CHECKING spinner; INTRO appears only once the probe round-trips,
        // so wait for it rather than asserting immediately.
        rule.onNodeWithText(Strings.Sync.fetch).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(Strings.Sync.intro).fetchSemanticsNodes().isNotEmpty()
        }
        pressBack()
        rule.onNodeWithText(Strings.Sync.fetch).assertIsDisplayed()

        // Settings: the version text opens the About dialog, whose button opens settings.
        rule.onNodeWithText(BuildConfig.GIT_VERSION).performClick()
        rule.onNodeWithText(Strings.About.settings).performClick()
        rule.onNodeWithText(Strings.Settings.title).assertIsDisplayed()
        pressBack()
        rule.onNodeWithText(Strings.Settings.title).assertDoesNotExist()

        rule.onNodeWithText("+").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("kjøttmei")
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("kjøttmeis").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("kjøttmeis").performClick()

        // The map picker owns its own BackHandler (shadows the app-level one) -> back to the editor.
        rule.onNodeWithText(Strings.Detail.locality).performClick()
        rule.onNodeWithText(Strings.Picker.titlePick).assertIsDisplayed()
        pressBack()
        rule.onNodeWithText(Strings.Detail.save).assertIsDisplayed()

        // The co-observer picker has no own handler; the app-level COOBS branch must catch it.
        rule.onNodeWithText(Strings.Detail.coObservers).performClick()
        rule.onNodeWithText(Strings.CoObs.title).assertIsDisplayed()
        pressBack()
        rule.onNodeWithText(Strings.Detail.save).assertIsDisplayed()
    }
}
