package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The picker's title doesn't fit between "Tilbake" and "Ny lokalitet" at the largest font size
 *  on a narrow phone, so it moves to its own row instead of showing as "V…". */
@RunWith(AndroidJUnit4::class)
class LargeTextHeaderTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun openPicker(fontScale: Float) {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                // A 360 dp wide phone, whatever the test device's width.
                Layout(content = { App(vm) }) { ms, c ->
                    val w = 360.dp.roundToPx()
                    val ps = ms.map { it.measure(c.copy(minWidth = w, maxWidth = w)) }
                    layout(w, c.maxHeight) { ps.forEach { it.place(0, 0) } }
                }
            }
        }
        rule.runOnUiThread { vm.openCurrentLocalityPicker() }
    }

    // The text itself, not its button: the buttons' touch targets overlap the title's row on purpose.
    private fun bounds(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun titleGetsItsOwnRowWithLargeText() {
        openPicker(fontScale = 2f)
        val title = bounds(Strings.Picker.titlePick)
        assertTrue((title.top + title.bottom) / 2 < bounds("‹ ${Strings.back}").top)
    }

    @Test
    fun titleSharesTheRowWithNormalText() {
        openPicker(fontScale = 1f)
        val title = bounds(Strings.Picker.titlePick)
        val back = bounds("‹ ${Strings.back}")
        assertEquals((back.top + back.bottom).value / 2, (title.top + title.bottom).value / 2, 1f)
    }
}
