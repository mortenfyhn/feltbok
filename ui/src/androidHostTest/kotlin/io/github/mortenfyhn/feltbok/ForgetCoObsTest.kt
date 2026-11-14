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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = loadedViewModel(p)
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
        assertEquals(listOf("Per"), loadedViewModel(p).coObserverOptions())   // persisted
    }

    @Test
    fun undoRestoresTheNamesAndOnlyTheDraftSwitchesTheyHad() {
        vm.forgetCoObs(setOf("Kari", "Ola"))
        vm.toggleCoObs("Per")   // a change made after the delete must survive the undo
        vm.undo()
        assertEquals(setOf("Kari", "Ola", "Per"), vm.coObserverOptions().toSet())
        assertEquals(listOf("Kari"), vm.dCoObs.toList())
        assertEquals(setOf("Kari", "Ola", "Per"), loadedViewModel(p).coObserverOptions().toSet())   // persisted
    }
}
