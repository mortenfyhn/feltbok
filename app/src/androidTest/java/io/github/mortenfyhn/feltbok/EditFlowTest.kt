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
 * Editor wiring that sits on top of already-tested logic: that "Kopier" carries the copied note's
 * locality into the fresh draft (#136-era), and that batch edit (#120) writes only to the marked
 * notes. `SelectionLogicTest` and `applyBatchEdit` cover the set-maths and the transform on the JVM;
 * what they can't see is whether the editor is actually handed the right notes and the result lands
 * back in the list.
 *
 * The fields themselves are set through the ViewModel rather than by driving the number pad: the
 * wiring under test is "Endre opens the batch editor for the marks, and Lagre applies to all of
 * them", not the count field's own input handling.
 */
@RunWith(AndroidJUnit4::class)
class EditFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun start(): MainViewModel {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent { App(vm) }
        return vm
    }

    /**
     * Copying must keep the locality the original already had. The centring that depends on it
     * (`pickerCenter(focused = true, …)`) is unit-tested but not assertable here — the map is an
     * AndroidView, so its centre never reaches the semantics tree.
     */
    @Test
    fun copyCarriesTheLocalityIntoTheNewDraft() {
        val vm = start()
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }

        rule.onNodeWithText("kjøttmeis", substring = true).performClick()
        rule.onNodeWithText(SEED_LOCALITY, substring = true).assertIsDisplayed()

        // Copy commits the open draft and slides in a near-identical one, still on this screen.
        rule.onNodeWithText(Strings.Detail.copy).performClick()
        rule.onNodeWithText(SEED_LOCALITY, substring = true).assertIsDisplayed()
    }

    @Test
    fun batchEditWritesOnlyToTheMarkedNotes() {
        val vm = start()
        val names = vm.species.map { it.norsk }.filter { it.isNotBlank() }.distinct().take(3)
        rule.runOnUiThread { names.forEach { vm.seedNote(it) } }

        // Newest first, so the two most recently seeded head the list; mark those and leave the third.
        val marked = listOf(names[2], names[1])
        val untouched = names[0]
        rule.runOnUiThread { vm.setSelection(vm.notes.take(2).map { it.id }) }

        rule.onNodeWithText(Strings.Notes.edit).performClick()
        rule.onNodeWithText(Strings.Notes.editTitle(2)).assertIsDisplayed()

        rule.runOnUiThread { vm.setCount(5) }
        rule.onNodeWithText(Strings.Detail.save).performClick()

        marked.forEach { rule.onNodeWithText("5 $it", substring = true).assertIsDisplayed() }
        rule.onNodeWithText("5 $untouched", substring = true).assertDoesNotExist()
    }
}
