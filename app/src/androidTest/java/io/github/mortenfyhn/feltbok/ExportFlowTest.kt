package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export screen on the minified releaseTest variant.
 */
@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    /**
     * Archiving from the export screen must report (and offer to undo) the notes it took. The
     * whole-list export scope aliases the live note list, so a bug there empties the undo payload
     * and the snackbar claims "0". One note, so no confirm dialog stands in the way.
     */
    @Test
    fun clearingTheExportReportsWhatItArchived() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(FakeArtsobs(AndroidPlatform(app)))
        rule.setContent { App(vm) }
        rule.runOnUiThread { vm.seedNote("kjøttmeis") }

        // Deleting is offered once everything is exported.
        rule.onNodeWithText(Strings.Notes.export).performClick()
        rule.onNodeWithText(Strings.Upload.button(1, all = true)).performClick()
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(Strings.Upload.clearExported(1)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(Strings.Upload.clearExported(1)).performClick()

        rule.onNodeWithText(Strings.Notes.deleted(1)).assertIsDisplayed()
        rule.onNodeWithText(Strings.Notes.undo).performClick()
        rule.onNodeWithText("kjøttmeis", substring = true).assertIsDisplayed()
    }

    /**
     * A whole export against a fake Artsobservasjoner, through the screen: the notes are sent, each
     * keeps its sighting id, and the screen ends on the result. The progress bar once crashed the app
     * right after the last note was sent, which only the screen could show.
     */
    @Test
    fun exportingSendsTheNotesAndShowsTheResult() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(FakeArtsobs(AndroidPlatform(app)))
        rule.setContent { App(vm) }
        rule.runOnUiThread { vm.seedNote("kjøttmeis"); vm.seedNote("blåmeis") }

        rule.onNodeWithText(Strings.Notes.export).performClick()
        rule.onNodeWithText(Strings.Upload.button(2, all = true)).performClick()
        // Halfway, the greyed button still says what was tapped.
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(Strings.Upload.exportedOf(1, 2)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(Strings.Upload.button(2, all = true)).assertIsNotEnabled()
        rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(Strings.Upload.allExported(2)).fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText(Strings.Upload.clearExported(2)).assertIsDisplayed() // offered once all are sent
        assertEquals(setOf(1L, 2L), vm.notes.mapNotNull { it.sightingId }.toSet())
    }

    /** Logged in as user 1, and stores each sighting as sent, under ids 1, 2, ... Takes a moment per
     *  sighting like the real site, so the screen draws the progress bar. */
    private class FakeArtsobs(real: Platform) : Platform by real {
        private var lastId = 0L

        override suspend fun siteCookie() = "session"

        override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
            if (url == Country.sessionUrl) HttpResponse(200, """[{"type":"user_id","value":"1"}]""") else HttpResponse(404, "")

        override suspend fun httpPostJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult {
            delay(300)
            val sent = JSONObject(body)
            val site = sent.optJSONObject("newSiteInfo")?.let {
                JSONObject().put("name", it.getString("siteName")).put("accuracy", it.getInt("accuracy"))
                    .put("latitude", it.getDouble("latitude")).put("longitude", it.getDouble("longitude"))
            } ?: JSONObject().put("id", sent.getLong("siteId"))
            val observers = JSONArray()
            sent.getJSONArray("observerIds").let { ids -> for (i in 0 until ids.length()) observers.put(JSONObject().put("id", ids.getLong(i))) }
            val stored = sent.put("id", ++lastId).put("site", site).put("taxon", JSONObject().put("id", sent.getLong("taxonId")))
                .put("observers", observers)
            return HttpResponse(201, stored.toString())
        }
    }
}
