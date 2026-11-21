package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalityGridTest {
    private fun circle(id: String, lat: Double, lon: Double, radius: Double = 100.0) =
        Locality(id, id, "", "", lat, lon, 0, radius)

    @Test
    fun returnsOnlyLocalitiesNearTheBoxInInputOrderWithoutDuplicates() {
        val far = circle("far", 70.0, 25.0)
        val wide = circle("wide", 63.0, 10.0, radius = 15_000.0) // spans several cells
        val near = circle("near", 63.01, 10.01)
        val grid = LocalityGrid(listOf(far, wide, near))
        assertEquals(listOf(wide, near), grid.query(63.0, 63.02, 10.0, 10.02))
    }

    @Test
    fun hugeLocalitiesAreAlwaysReturned() {
        val sea = circle("sea", 66.0, 5.0, radius = 200_000.0)
        val grid = LocalityGrid(listOf(sea))
        assertEquals(listOf(sea), grid.query(59.0, 59.01, 10.0, 10.01))
    }
}
