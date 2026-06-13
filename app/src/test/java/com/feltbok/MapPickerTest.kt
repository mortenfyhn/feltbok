package com.feltbok

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPickerTest {
    // 1 px/m, a plausible mid-zoom scale; keeps the radii vs the 140 px threshold easy to read.
    private val ppm = 1.0

    private fun circle(radius: Double) =
        Locality("c", "Circle", "", "", 63.0, 8.0, 0, radius)

    private fun square(latLo: Double, latHi: Double, lonLo: Double, lonHi: Double) = listOf(
        doubleArrayOf(latLo, lonLo), doubleArrayOf(latLo, lonHi),
        doubleArrayOf(latHi, lonHi), doubleArrayOf(latHi, lonLo),
    )

    @Test
    fun wideCircleSelectionIsFaint() {
        // The regression: Uttian is a wide *circle*, not a polygon. A polygon-only test painted it
        // opaque and buried every locality inside it. A large footprint must read as faint.
        assertTrue(selectedFillIsFaint(circle(2000.0), ppm))
    }

    @Test
    fun smallDotSelectionIsSolid() {
        // Small circles and point localities should stay solid so the dot still stands out.
        assertFalse(selectedFillIsFaint(circle(50.0), ppm))
        assertFalse(selectedFillIsFaint(circle(0.0), ppm))
    }

    @Test
    fun polygonSelectionIsFaint() {
        val poly = Locality("p", "Area", "", "", 63.5, 8.5, 0, 0.0, polygon = square(63.0, 64.0, 8.0, 9.0))
        assertTrue(selectedFillIsFaint(poly, ppm))
    }
}
