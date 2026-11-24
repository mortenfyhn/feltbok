package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A note remembers that it was sent, so an export, on this visit or a later one, sends only the
 *  rest. Exporting again and deleting go by the same mark. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportVisitTest {
    private val p = FakePlatform()
    private lateinit var vm: MainViewModel
    private var lastId = 0L
    private var offlineAfter = Int.MAX_VALUE // how many POSTs get through before the connection goes

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        p.cookie = "c"
        p.answers["/bff/user"] = { """[{"type":"user_id","value":"58758"}]""" }
        p.answers["TaxonName/Search"] = { """[{"taxonId":3681,"speciesGroupId":8,"scientificName":{"name":"Larus canus"}}]""" }
        p.postAnswer = { body ->
            if (p.posts.size > offlineAfter) NoAnswer(offline = true)
            else HttpResponse(201, body.dropLast(1) + ""","id":${++lastId},"site":{"id":352401},"taxon":{"id":3681},"observers":[{"id":58758}]}""")
        }
        vm = loadedViewModel(p)
        val t = nowMs() - 3_600_000
        for (id in 1L..3L) {
            vm.notes += Note(
                id = id, time = t + id, species = "fiskemåke", latin = "Larus canus", count = 1, age = "", activity = "",
                sex = "", publicComment = "", privateComment = "", locName = "Uttian", locFull = "", lat = 63.77,
                lon = 8.87, locId = "352401",
            )
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun export(start: () -> Unit = { vm.uploadExported() }) {
        start()
        val until = System.currentTimeMillis() + 5000
        while (vm.upload !is UploadState.Finished) {
            check(System.currentTimeMillis() < until) { "timed out waiting for the export" }
            Thread.sleep(10)
        }
    }

    private fun sent() = vm.notes.filter { it.sightingId != null }.map { it.id }.sorted()

    @Test
    fun aRetrySendsOnlyTheUnsent() {
        offlineAfter = 1
        vm.openExport()
        export()
        assertEquals(Interruption.Failed("Mista internettilkoblinga"), ((vm.upload as UploadState.Finished).result as UploadResult.Done).stopped)
        assertEquals(listOf(1L), sent())

        offlineAfter = Int.MAX_VALUE
        p.posts.clear()
        export()
        assertEquals(2, p.posts.size)
        assertEquals(listOf(1L, 2L, 3L), sent())
    }

    // Regression: a later visit sent every note again, duplicating the ones already on the site.
    @Test
    fun aLaterVisitSendsOnlyNotesAddedSince() {
        vm.openExport()
        export()
        vm.closeExport()
        vm.notes += vm.notes[0].copy(id = 4L, sightingId = null)

        vm.openExport()
        p.posts.clear()
        export()
        assertEquals(1, p.posts.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), sent())
    }

    @Test
    fun exportAgainSendsEveryNote() {
        vm.openExport()
        export()
        p.posts.clear()
        export { vm.exportAgain() }
        assertEquals(3, p.posts.size)
        assertEquals(listOf(4L, 5L, 6L), vm.notes.mapNotNull { it.sightingId }.sorted()) // the new sightings
    }

    @Test
    fun clearingDeletesOnlyTheSentNotes() {
        offlineAfter = 1
        vm.openExport()
        export()
        vm.clearExported()
        assertEquals(listOf(2L, 3L), vm.notes.map { it.id }.sorted())
    }
}
