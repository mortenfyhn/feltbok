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

/** With location off (no permission, or switched off) no fix will come, so the status strip must
 *  not keep saying it's searching (#196). */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationOffTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val loc = Locality("1", "Rådhuset", "", "", 63.7, 8.8, 0, 50.0)
    private val fix = GpsFix(63.7, 8.8, 5f, 0L)

    @Test
    fun searchingOnlyWhileLocationIsOn() {
        assertEquals(Strings.findingPosition to Strings.Notes.searchingGps, statusLines(null, null, locationOff = false))
        assertEquals(Strings.Notes.noLocality to Strings.Notes.gpsOff, statusLines(null, null, locationOff = true))
    }

    @Test
    fun aPickedLocalityKeepsItsNameWithLocationOff() {
        assertEquals("Rådhuset" to Strings.Notes.gpsOff, statusLines(loc, null, locationOff = true))
        assertTrue(statusLines(loc, fix, locationOff = false).second.contains("GPS ±5 m"))
    }

    @Test
    fun theViewModelFollowsTheLocationSource() {
        val platform = FakePlatform()
        val vm = MainViewModel(platform)
        val off = platform.locationOff
        off.value = true
        assertTrue(vm.locationOff)
        off.value = false
        assertEquals(false, vm.locationOff)
    }
}
