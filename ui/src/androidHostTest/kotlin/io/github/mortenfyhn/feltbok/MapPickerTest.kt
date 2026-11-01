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

    // A rectangular polygon with a bounding box of the given size in metres, so tap-gate tests can
    // reason in pixels directly (at ppm = 1.0 a metre is a pixel). 1 m ≈ 1/111320° lat; lon is
    // scaled by cos(lat) so the metre→degree→metre round-trip lands back on widthM/heightM.
    private val lat = 63.0
    private fun polygonMeters(widthM: Double, heightM: Double): Locality {
        val dLat = heightM / 111_320.0
        val dLon = widthM / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return Locality("p", "Poly", "", "", lat, 8.0, 0, 0.0, polygon = square(lat, lat + dLat, 8.0, 8.0 + dLon))
    }

    // A 1000 x 2000 px portrait viewport, like a phone held upright (width is the short side).
    private val viewW = 1000
    private val viewH = 2000

    // The tap-gate tests size footprints relative to this so they verify the per-axis logic, not a
    // specific tuned value - the fraction is a knob the maintainer changes freely.
    private val f = MAX_TAP_FIT_FRACTION.toDouble()

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
    fun footprintWithinTheBudgetIsTappable() {
        // A footprint comfortably inside the per-axis budget (f x viewport) on both axes stays
        // tappable. Småbergan (a tall, narrow polygon that fits the long screen axis) is this case.
        assertFalse(tooBigToTap(polygonMeters(0.9 * f * viewW, 0.9 * f * viewH), ppm = 1.0, viewW, viewH))
    }

    @Test
    fun footprintBeyondTheBudgetOnEitherAxisIsNotTappable() {
        // Just past the budget on width -> excluded...
        assertTrue(tooBigToTap(polygonMeters(1.1 * f * viewW, 0.5 * f * viewH), ppm = 1.0, viewW, viewH))
        // ...and just past it on height -> excluded.
        assertTrue(tooBigToTap(polygonMeters(0.5 * f * viewW, 1.1 * f * viewH), ppm = 1.0, viewW, viewH))
    }

    @Test
    fun gateComparesEachAxisToItsOwnViewportSide() {
        // The same footprint is judged per axis. A long-and-thin one fits when its long side lies
        // along the screen's long (tall) axis (Småbergan, upright)...
        val longSide = 0.9 * f * viewH
        val shortSide = 0.3 * f * viewW
        assertFalse(tooBigToTap(polygonMeters(shortSide, longSide), ppm = 1.0, viewW, viewH))
        // ...but rotated so the long side lies along the short (width) axis, it overflows -> excluded.
        // (Catches the original bug: measuring the larger extent against the short side regardless.)
        assertTrue(tooBigToTap(polygonMeters(longSide, shortSide), ppm = 1.0, viewW, viewH))
    }

    @Test
    fun hugeFootprintZoomedIntoIsNotTappable() {
        // The point of the gate (Hønstadvatnet at the rim of Jonsvatnet): a footprint that overflows
        // the screen has no visible edge to aim at, so it must never grab a tap meant for a small
        // locality inside it. Same polygon fits at low zoom, overflows zoomed in (higher ppm).
        val poly = polygonMeters(0.5 * f * viewW, 0.5 * f * viewH)
        assertFalse(tooBigToTap(poly, ppm = 1.0, viewW, viewH))  // fits
        assertTrue(tooBigToTap(poly, ppm = 3.0, viewW, viewH))   // 3x on screen -> 1.5x budget -> excluded
    }

    @Test
    fun circlesGateOnDiameterAndPointsNeverDo() {
        // A circle is symmetric, so its diameter is gated against the short side; a footprint-less
        // point has no area and is always tappable, however far zoomed in.
        assertFalse(tooBigToTap(circle(0.45 * f * viewW), ppm = 1.0, viewW, viewH))  // diameter 0.9x budget
        assertTrue(tooBigToTap(circle(0.6 * f * viewW), ppm = 1.0, viewW, viewH))    // diameter 1.2x budget
        assertFalse(tooBigToTap(circle(0.0), ppm = 1000.0, viewW, viewH))            // point: never too big
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
