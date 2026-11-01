package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

class PickerCenterTest {
    private fun loc(lat: Double, lon: Double) = Locality("", "L", "", "", lat, lon, 0, 0.0)
    private fun fix(lat: Double, lon: Double) = GpsFix(lat, lon, 5f, 0L)

    private val locality = loc(63.0, 10.0)
    private val gps = fix(59.0, 5.0)

    // #180: the locality wins over the GPS fix however far apart they are. A new observation
    // inherits the current locality, which sticks after you pick one by hand or save a note there,
    // so it can sit far from where you now stand - and the picker must still show it.
    @Test
    fun localityWinsOverGps() {
        val (lat, lon) = pickerCenter(focus = locality, fix = gps, nearest = null)
        assertEquals(63.0, lat, 0.0)
        assertEquals(10.0, lon, 0.0)
    }

    // With no locality yet, centre on where you are now so you can place one near you.
    @Test
    fun noLocalityCentresOnGps() {
        val (lat, lon) = pickerCenter(focus = null, fix = gps, nearest = null)
        assertEquals(59.0, lat, 0.0)
        assertEquals(5.0, lon, 0.0)
    }
}
