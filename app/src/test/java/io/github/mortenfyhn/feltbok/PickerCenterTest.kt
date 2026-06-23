package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerCenterTest {
    private fun loc(lat: Double, lon: Double) = Locality("", "L", "", "", lat, lon, 0, 0.0)
    private fun fix(lat: Double, lon: Double) = GpsFix(lat, lon, 5f, 0L)

    private val locality = loc(63.0, 10.0)
    private val gps = fix(59.0, 5.0)

    // The regression: copying an observation then editing its location must centre on the locality
    // the copy already has, not on the current GPS fix. `focused` covers editing/copying/current.
    @Test
    fun focusedCentresOnLocalityNotGps() {
        val (lat, lon) = pickerCenter(focused = true, focus = locality, fix = gps, dLoc = locality, nearest = null)
        assertEquals(63.0, lat, 0.0)
        assertEquals(10.0, lon, 0.0)
    }

    // A new (unfocused) observation centres on where you are now so you can place a locality near you.
    @Test
    fun unfocusedCentresOnGps() {
        val (lat, lon) = pickerCenter(focused = false, focus = locality, fix = gps, dLoc = locality, nearest = null)
        assertEquals(59.0, lat, 0.0)
        assertEquals(5.0, lon, 0.0)
    }
}
