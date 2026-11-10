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

/** Deleting names from the co-observer picker, and undoing it (#191). */
@OptIn(ExperimentalCoroutinesApi::class)
class ForgetCoObsTest {
    private val p = FakePlatform()
    private lateinit var vm: MainViewModel

    /** Main is unconfined here, so the bundled-locality load finishes on a background thread. Let it
     *  land, or it races resetMain in [tearDown] (as in LoginFlowTest). */
    private fun newVm(): MainViewModel {
        val v = MainViewModel(p)
        val until = System.currentTimeMillis() + 5000
        while (!v.localitiesLoaded) {
            check(System.currentTimeMillis() < until) { "timed out waiting for bundled localities" }
            Thread.sleep(10)
        }
        return v
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = newVm()
        listOf("Kari", "Ola", "Per").forEach(vm::addCoObs)
        vm.toggleCoObs("Ola")   // known, but not on this observation
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun forgettingDropsNamesFromTheListAndTheDraft() {
        vm.forgetCoObs(setOf("Kari", "Ola"))
        assertEquals(listOf("Per"), vm.coObserverOptions())
        assertEquals(listOf("Per"), vm.dCoObs.toList())
        assertEquals(listOf("Per"), newVm().coObserverOptions())   // persisted
    }

    @Test
    fun undoRestoresTheNamesAndOnlyTheDraftSwitchesTheyHad() {
        vm.forgetCoObs(setOf("Kari", "Ola"))
        vm.toggleCoObs("Per")   // a change made after the delete must survive the undo
        vm.undo()
        assertEquals(setOf("Kari", "Ola", "Per"), vm.coObserverOptions().toSet())
        assertEquals(listOf("Kari"), vm.dCoObs.toList())
        assertEquals(setOf("Kari", "Ola", "Per"), newVm().coObserverOptions().toSet())   // persisted
    }
}
