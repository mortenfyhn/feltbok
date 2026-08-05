package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export flow on the minified releaseTest variant: with an observation present, open the export
 * screen, confirm the TSV renders (header columns + the observation), and that Copy works. Pairs
 * with [AddObservationFlowTest] - that one covers capture, this one the paste-to-Artsobservasjoner
 * hand-off (#142). Whole-format TSV correctness is unit-tested in ModelTest; this guards the screen
 * plumbing around it (does the field show, does Copy fire) on the shrunk variant.
 */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun exportShowsTsvAndCopies() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)
        rule.setContent { App(vm) }

        // Seed one observation directly - the capture UI is AddObservationFlowTest's job; here we
        // just need something to export. (Same draft path the editor drives, see that test's note.)
        rule.runOnUiThread {
            vm.startAdd()
            vm.pickSpecies(vm.species.first { it.norsk == "kjøttmeis" })
            vm.createNewLocality(63.43, 10.39, 100, "Testlokalitet")
            vm.save()
        }

        // Export is reachable from the list's top-right button (shown only with notes present).
        rule.onNodeWithText(Strings.Notes.export).performClick()

        // The whole TSV (header + rows) renders in one field. Match the node carrying both a header
        // column ("Artsnavn", export-only) and the seeded species - uniquely the TSV field, not the
        // list row underneath the overlay (which has the species but no header).
        rule.onNode(hasText("Artsnavn", substring = true) and hasText("kjøttmeis", substring = true))
            .assertIsDisplayed()

        // Copy commits the TSV to the clipboard and flips the button label.
        rule.onNodeWithText(Strings.Export.copy).performClick()
        rule.onNodeWithText(Strings.Export.copied).assertIsDisplayed()
    }
}
