package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewLocalityTest {
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = MainViewModel(FakePlatform())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // #196: a spot placed from the status strip's picker becomes the current locality and returns to
    // the list, instead of opening a blank observation and leaving pickingCurrent set.
    @Test
    fun newSpotFromStatusStripBecomesCurrent() {
        vm.openCurrentLocalityPicker()
        vm.createNewLocality(59.9, 10.7, 50, "Brygga")
        assertEquals(Screen.LIST, vm.screen)
        assertFalse(vm.pickingCurrent)
        assertEquals("Brygga", vm.currentLocality()?.lokalitet)
        assertNull(vm.dLoc)
    }

    @Test
    fun newSpotFromEditorGoesToTheDraft() {
        vm.openLocalityPicker()
        vm.createNewLocality(59.9, 10.7, 50, "Brygga")
        assertEquals(Screen.DETAIL, vm.screen)
        assertEquals("Brygga", vm.dLoc?.lokalitet)
    }
}
