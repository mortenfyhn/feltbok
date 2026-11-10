package io.github.mortenfyhn.feltbok

import io.github.mortenfyhn.feltbok.FakePlatform.Companion.loggedInClaims
import io.github.mortenfyhn.feltbok.FakePlatform.Companion.site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The view model's locality updates over a fake web: the Oppdater button, the login's return and
 *  the map's cue to fetch your own localities. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowTest {
    private val p = FakePlatform()
    private lateinit var vm: MainViewModel

    // A small view in Oslo, well inside one live-fetch group.
    private val view = doubleArrayOf(59.90, 10.70, 59.91, 10.72)

    /** Settle the map on [v]. Until the bundled localities have loaded the view model holds the
     *  view back (and only looks it up later on the picker screen), so repeat until it asks. */
    private fun pan(v: DoubleArray = view) {
        val before = p.requestsTo("ByBoundingBox").size
        await("map fetch to start") {
            vm.fetchLiveSites(v[0], v[1], v[2], v[3])
            p.requestsTo("ByBoundingBox").size > before
        }
    }

    private var publicRows = listOf(site(1, "Public 1", false))
    private var privateRows = listOf<String>()
    private var mine: List<String>? = listOf(site(10, "Mine 1", true))

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        p.answers["/bff/user"] = { if (p.cookie != null) loggedInClaims() else null }
        p.answers["ByBoundingBox"] = { "[" + (publicRows + privateRows).joinToString(",") + "]" }
        p.answers["ByUser"] = { mine?.let { """{"data":[${it.joinToString(",")}],"totalPages":1}""" } }
        vm = MainViewModel(p)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Wait for the view model's background work (it parses and saves off the main thread). */
    private fun await(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun logIn() {
        p.cookie = "__Host-bff=abc"
        vm.checkSession()
        await("login check") { vm.loggedIn }
    }

    private fun lastToast() = p.toasts.lastOrNull()

    @Test
    fun updateFetchesYourOwnLocalitiesAndSaysWhatChanged() {
        logIn()
        vm.updateLocalities()
        await("toast") { p.toasts.size == 1 }
        assertEquals("Henta 1 privat lokalitet", lastToast())
        assertEquals(listOf("10"), vm.localities.filter { it.mine }.map { it.id })
        assertFalse(vm.mySitesBusy)

        vm.updateLocalities()
        await("toast") { p.toasts.size == 2 }
        assertEquals("Allerede oppdatert", lastToast())

        mine = listOf(site(10, "Mine 1 renamed", true), site(11, "Mine 2", true))
        vm.updateLocalities()
        await("toast") { p.toasts.size == 3 }
        assertEquals("Henta 2 endra private lokaliteter", lastToast())
    }

    @Test
    fun updateFetchesTheViewAgainAndGivesOneToastForBoth() {
        logIn()
        pan()
        await("first fetch") { vm.localities.any { it.id == "1" } }
        val before = p.requestsTo("ByBoundingBox").size

        // The view was checked moments ago; Oppdater fetches it again anyway.
        publicRows = publicRows + site(2, "Public 2", false)
        vm.updateLocalities()
        await("toast") { p.toasts.any { it.startsWith("Henta") && "privat" in it } }
        assertTrue(p.requestsTo("ByBoundingBox").size > before)
        assertTrue(vm.localities.any { it.id == "2" })
        assertEquals("Henta 1 allmenn lokalitet og 1 privat lokalitet", lastToast())
        assertFalse("no separate map toast", p.toasts.any { it == Strings.Picker.liveFetched(1) })
    }

    @Test
    fun updateSaysSoWhenYourLocalitiesCantBeFetched() {
        logIn()
        mine = null
        vm.updateLocalities()
        await("toast") { p.toasts.isNotEmpty() }
        assertEquals(Strings.Sync.error, lastToast())
        assertFalse(vm.mySitesBusy)
    }

    @Test
    fun updateSaysSoWhenYourLocalitiesCantBeSaved() {
        logIn()
        p.failWrites = true
        vm.updateLocalities()
        await("toast") { p.toasts.isNotEmpty() }
        assertEquals(Strings.Sync.error, lastToast())
        assertFalse(vm.mySitesBusy)
    }

    @Test
    fun aPrivateSiteTheAppDoesntKnowSetsOffOneFetchOfYourOwn() {
        logIn()
        // 10 is yours; 20 is someone else's that you can see. ByUser returns only 10.
        privateRows = listOf(site(10, "Mine 1", true), site(20, "Theirs", true))
        pan()
        await("ByUser") { p.requestsTo("ByUser").size == 1 }
        await("mine applied") { vm.localities.any { it.id == "10" } }
        await("not busy") { !vm.mySitesBusy }
        assertEquals("Henta 1 privat lokalitet", lastToast())
        assertFalse("other people's private sites are never kept", vm.localities.any { it.id == "20" })

        // Elsewhere, 20 turns up again: it's known not to be yours, so no second fetch.
        pan(doubleArrayOf(60.90, 10.70, 60.91, 10.72))
        await("second view fetched") { p.requestsTo("ByBoundingBox").any { "MinY=60.9" in it.first } }
        Thread.sleep(200)
        assertEquals(1, p.requestsTo("ByUser").size)
    }

    @Test
    fun loggedOutTheMapNeverFetchesYourOwn() {
        privateRows = listOf(site(20, "Theirs", true))
        pan()
        await("map fetch") { vm.localities.any { it.id == "1" } }
        Thread.sleep(200)
        assertTrue(p.requestsTo("ByUser").isEmpty())
        assertFalse(vm.localities.any { it.id == "20" })
    }

    @Test
    fun comingBackFromTheLoginUpdatesInThePicker() {
        p.cookie = "__Host-bff=abc" // the login screen's WebView now holds the session
        vm.closeSync(loggedIn = true)
        assertEquals(Screen.LOCALITY, vm.screen)
        await("toast") { p.toasts.isNotEmpty() }
        assertEquals("Henta 1 privat lokalitet", lastToast())
        await("login check") { vm.loggedIn }
    }
}
