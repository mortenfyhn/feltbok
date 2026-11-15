package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The export screen remembers what it sent only while it's open: a retry sends the rest, and
 *  leaving marks the rest in the list. The next visit sends what it's asked to. */
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
        vm = MainViewModel(p)
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

    private fun export() {
        vm.uploadExported()
        val until = System.currentTimeMillis() + 5000
        while (vm.upload !is UploadState.Finished) {
            check(System.currentTimeMillis() < until) { "timed out waiting for the export" }
            Thread.sleep(10)
        }
    }

    @Test
    fun aRetrySendsOnlyWhatThisVisitDidNot() {
        offlineAfter = 1
        vm.openExport()
        export()
        assertEquals(Interruption.Failed("Mista internettilkoplinga"), ((vm.upload as UploadState.Finished).result as UploadResult.Done).stopped)
        assertEquals(listOf(1L), vm.sentThisVisit.toList())

        offlineAfter = Int.MAX_VALUE
        p.posts.clear()
        export()
        assertEquals(2, p.posts.size)
        assertEquals(3, vm.sentThisVisit.size)
    }

    @Test
    fun leavingHalfwayMarksTheRestAndTheNextVisitSendsWhatItIsAskedTo() {
        offlineAfter = 1
        vm.openExport()
        export()
        vm.closeExport()
        assertEquals(listOf(2L, 3L), vm.selected.toList())

        offlineAfter = Int.MAX_VALUE
        vm.openExport() // all of them, though note 1 was sent before
        assertTrue(vm.sentThisVisit.isEmpty())
        p.posts.clear()
        export()
        assertEquals(3, p.posts.size)
    }
}
