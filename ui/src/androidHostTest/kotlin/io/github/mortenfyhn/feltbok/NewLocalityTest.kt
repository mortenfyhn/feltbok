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
import org.junit.Assert.assertTrue
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

    // Placing a spot stays on the map, so you see it and tap it to pick it.
    @Test
    fun newSpotStaysOnTheMap() {
        vm.openLocalityPicker()
        val loc = vm.createNewLocality(59.9, 10.7, 50, "Brygga")
        assertEquals(Screen.LOCALITY, vm.screen)
        assertTrue(loc in vm.localities)
    }

    // #196: a spot placed from the status strip's picker becomes the current locality and returns to
    // the list, instead of opening a blank observation and leaving pickingCurrent set.
    @Test
    fun newSpotFromStatusStripBecomesCurrent() {
        vm.openCurrentLocalityPicker()
        vm.pickLocality(vm.createNewLocality(59.9, 10.7, 50, "Brygga"))
        assertEquals(Screen.LIST, vm.screen)
        assertFalse(vm.pickingCurrent)
        assertEquals("Brygga", vm.currentLocality()?.lokalitet)
        assertNull(vm.dLoc)
    }

    // A spot only outlives a restart through a live note that uses it, so restoring that note
    // from the archive must make the spot pickable again without another restart.
    @Test
    fun restoringANoteBringsBackItsNewSpot() {
        val platform = FakePlatform()
        appendToArchive(platform.storage, listOf(Note(id = 1L, species = "kjøttmeis", latin = "", count = 1,
            age = "", activity = "", sex = "", publicComment = "", privateComment = "",
            locName = "Brygga", locFull = "Brygga", lat = 59.9, lon = 10.7, newLoc = true, locRadius = 50)))
        vm = MainViewModel(platform)
        vm.openArchive()
        vm.toggleSelect(1L)
        vm.restoreSelected()
        assertTrue(vm.localities.any { it.newLoc && it.lokalitet == "Brygga" && it.radius == 50.0 })
    }

    @Test
    fun newSpotFromEditorGoesToTheDraft() {
        vm.openLocalityPicker()
        vm.pickLocality(vm.createNewLocality(59.9, 10.7, 50, "Brygga"))
        assertEquals(Screen.DETAIL, vm.screen)
        assertEquals("Brygga", vm.dLoc?.lokalitet)
    }
}
