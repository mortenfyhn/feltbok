package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun smallestFootprintWinsOverContainer() {
        // The #126 fix: a point and a small polygon both hit, nested inside a big container. The
        // smallest footprint must win so the specific locality is selectable, not the container.
        val point = TapCandidate(circle(0.0), spanPx = 0f, centreDistPx = 30f)
        val small = TapCandidate(circle(50.0), spanPx = 50f, centreDistPx = 5f)
        val uttian = TapCandidate(circle(2000.0), spanPx = 2000f, centreDistPx = 1f)
        // Even though the container's centre is nearest, the point (smallest) wins.
        assertEquals(point.loc, resolveTap(listOf(uttian, small, point)))
        // Drop the point: the small polygon still beats the container.
        assertEquals(small.loc, resolveTap(listOf(uttian, small)))
    }

    @Test
    fun tinyLocalitiesAreDeclutteredWhenZoomedOut() {
        // You can only tap what's drawn: a tiny point (span 0) is hidden far out, so it can't steal
        // a tap meant for a visible polygon (#126). Once zoomed in, the same point shows and is tappable.
        assertTrue(declutteredAtZoom(zoom = 12.0, spanPx = 0f))
        assertFalse(declutteredAtZoom(zoom = 16.0, spanPx = 0f))
        // A footprint big enough to draw stays visible (and tappable) even when zoomed out.
        assertFalse(declutteredAtZoom(zoom = 12.0, spanPx = 30f))
    }

    @Test
    fun nearestCentreBreaksEqualSizeTies() {
        // Two overlapping point dots (span 0): nearest centre decides.
        val near = TapCandidate(Locality("near", "Near", "", "", 63.0, 8.0, 0, 0.0), spanPx = 0f, centreDistPx = 10f)
        val far = TapCandidate(Locality("far", "Far", "", "", 63.0, 8.0, 0, 0.0), spanPx = 0f, centreDistPx = 20f)
        assertEquals(near.loc, resolveTap(listOf(far, near)))
        assertNull(resolveTap(emptyList()))
    }
}
