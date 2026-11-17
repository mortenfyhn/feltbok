package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultLocalityTest {
    private fun point(name: String, lat: Double, lon: Double) = Locality(name, name, "", "", lat, lon, 0, 0.0)
    private fun square(id: String, lat0: Double, lat1: Double, lon0: Double, lon1: Double) =
        Locality(id, id, "", "", (lat0 + lat1) / 2, (lon0 + lon1) / 2, 0, 0.0,
            polygon = listOf(
                doubleArrayOf(lat0, lon0), doubleArrayOf(lat0, lon1),
                doubleArrayOf(lat1, lon1), doubleArrayOf(lat1, lon0),
            ))
    private val metresPerDegLat = 111_195.0

    @Test
    fun pointRadiusIs200mOrHalfwayToTheNearestPoint() {
        val lone = point("lone", 60.0, 10.0)
        val a = point("a", 63.0, 8.0)
        val b = point("b", 63.0 + 300 / metresPerDegLat, 8.0) // 300 m north of a
        val circle = Locality("c", "c", "", "", 63.0, 8.001, 0, 50.0) // right next to a, but not a point
        val radii = pointRadii(listOf(lone, a, b, circle))
        assertEquals(200.0, radii[0])
        assertEquals(150.0, radii[1], 0.5)
        assertEquals(150.0, radii[2], 0.5)
        assertEquals(0.0, radii[3])
    }

    @Test
    fun pointInsideAnAreaWinsWithinItsRadius() {
        val lake = square("lake", 63.0, 63.1, 8.0, 8.2)
        val bay = point("bay", 63.05, 8.1)
        val all = listOf(lake, bay)
        val radii = pointRadii(all)
        assertEquals(bay, defaultLocality(all, radii, 63.05 + 150 / metresPerDegLat, 8.1))
        assertEquals(lake, defaultLocality(all, radii, 63.05 + 250 / metresPerDegLat, 8.1))
    }

    @Test
    fun outsideEverythingThePointRanksByItsDiskEdge() {
        val point = point("point", 63.0, 8.0)
        val circle = Locality("circle", "circle", "", "", 63.0 + 600 / metresPerDegLat, 8.0, 0, 50.0)
        val all = listOf(point, circle)
        // 300 m from both the point's centre and the circle's edge: the point's disk edge is 100 m away.
        assertEquals(point, defaultLocality(all, pointRadii(all), 63.0 + 300 / metresPerDegLat, 8.0))
    }
}
