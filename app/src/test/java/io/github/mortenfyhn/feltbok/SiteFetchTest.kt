package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteFetchTest {
    // Trimmed from a real ByBoundingBox response: polygonCoordinates is a JSON array, not a string.
    private val json = """
        [
          {"id":1,"name":"Parent","latitude":59.9,"longitude":10.7,"isPrivate":false,"accuracy":500,
           "municipalityName":"Oslo","parentSiteId":null,"isPolygon":false,"polygonCoordinates":null},
          {"id":2,"name":"Child","latitude":59.91,"longitude":10.71,"isPrivate":false,"accuracy":20,
           "municipalityName":"Oslo","parentSiteId":1,"isPolygon":true,
           "polygonCoordinates":[[10.70,59.90],[10.72,59.90],[10.72,59.92]]},
          {"id":3,"name":"Orphan","latitude":59.92,"longitude":10.72,"isPrivate":false,"accuracy":0,
           "municipalityName":"Oslo","parentSiteId":99,"isPolygon":false,"polygonCoordinates":null},
          {"id":4,"name":"Someone's own","latitude":59.93,"longitude":10.73,"isPrivate":true,
           "parentSiteId":null,"isPolygon":false,"polygonCoordinates":null}
        ]
    """.trimIndent()

    @Test
    fun parsePublicSitesDropsPrivateAndResolvesParents() {
        // Private rows are strangers' own sites and must never be shown or stored. A parent outside
        // the box is looked up in the already-loaded list.
        val sites = parsePublicSites(jsonRows(json)) { if (it == "99") "Known" else null }.associateBy { it.id }
        assertEquals(setOf("1", "2", "3"), sites.keys)
        assertEquals("Parent", sites.getValue("2").hovedlokalitet)
        assertEquals("Known", sites.getValue("3").hovedlokalitet)
        assertTrue("named as a parent => super", sites.getValue("1").isSuper)
        val child = sites.getValue("2")
        assertEquals(20.0, child.radius, 1e-9)
        assertEquals("lat first", 59.90, child.polygon[0][0], 1e-9)
        assertTrue(child.public && !child.mine)
    }

    @Test
    fun liveRowsSurviveTheCsvRoundTrip() {
        // Fetched rows are appended to live-localities.csv and read back at the next launch.
        for (s in parsePublicSites(jsonRows(json)) { null }) {
            val back = parseLocalityRow(parseCsvLine(liveLocalityRow(s).trimEnd('\n')))!!
            assertEquals(s.id, back.id); assertEquals(s.lokalitet, back.lokalitet)
            assertEquals(s.hovedlokalitet, back.hovedlokalitet); assertEquals(s.isSuper, back.isSuper)
            assertEquals(s.radius, back.radius, 1e-9); assertEquals(s.polygon.size, back.polygon.size)
            assertTrue(back.public && !back.mine)
        }
    }

    @Test
    fun cellBoxCoversTheViewAndStaysUnderTheServerLimit() {
        // The request box is the visible box snapped out to whole cells; the server rejects > 50 km.
        val s = 69.6; val w = 18.81
        val e = w + 0.999 * LIVE_FETCH_MAX_M / mercX(1.0) // about the widest allowed view
        val n = s + 0.03
        assertTrue(fitsSiteQuery(s, w, n, e))
        val (bs, bw, bn, be) = cellsBox(cellsIn(s, w, n, e))
        assertTrue("covers the view", bs <= s && bw <= w && bn >= n && be >= e)
        assertTrue(mercX(be) - mercX(bw) < 50_000 && mercY(bn) - mercY(bs) < 50_000)
    }

    @Test
    fun splittingCellsEndsAtSingleCellsAndKeepsEveryCell() {
        // Truncated responses are retried on smaller boxes; that must terminate and lose nothing,
        // including west of Greenwich (Jan Mayen), where the cell indices are negative.
        for (box in listOf(doubleArrayOf(63.30, 10.30, 63.35, 10.40), doubleArrayOf(70.90, -8.80, 70.95, -8.70))) {
            val all = cellsIn(box[0], box[1], box[2], box[3])
            fun leaves(cells: List<Long>): List<Long> =
                if (cells.size == 1) cells else splitCells(cells).also { parts -> assertTrue(parts.all { it.size < cells.size }) }.flatMap(::leaves)
            assertEquals(all.toSet(), leaves(all).toSet())
            assertEquals(all.size, leaves(all).size)
        }
    }

    @Test
    fun fetchCellsSplitsTruncatedResponsesAndSkipsFailedParts() = runBlocking {
        // A fake server over a 4x4-cell area with 150 sites per cell: the whole area (2400 sites)
        // gets cut off at 1000 rows like the real one does, each 2x2 quarter (600) answers in full.
        val (x0, y0) = cellsIn(63.3, 10.3, 63.3, 10.3).single().let { (it shr 32).toInt() to it.toInt() }
        val cells = (x0..x0 + 3).flatMap { x -> (y0..y0 + 3).map { y -> (x.toLong() shl 32) or (y.toLong() and 0xffffffffL) } }
        val sites = cells.flatMap { c ->
            val (s, w, n, e) = cellsBox(listOf(c))
            (1..150).map { i ->
                JSONObject().put("id", "$c$i".hashCode()).put("isPrivate", false)
                    .put("latitude", s + (n - s) * (i % 10 + 0.5) / 10).put("longitude", w + (e - w) * (i / 10 + 0.5) / 16)
            }
        }
        fun inBox(s: Double, w: Double, n: Double, e: Double) =
            sites.filter { it.getDouble("latitude") in s..n && it.getDouble("longitude") in w..e }

        val (done, rows) = fetchCells(cells) { s, w, n, e -> inBox(s, w, n, e).take(1000) }
        assertEquals("every cell complete", cells.toSet(), done.toSet())
        assertEquals("every site found", sites.size, rows.distinctBy { it.getInt("id") }.size)

        // The quarter holding the first cell fails (offline): only its cells stay unchecked.
        val dead = cellsBox(listOf(cells[0]))
        val (done2, _) = fetchCells(cells) { s, w, n, e ->
            val rows2 = inBox(s, w, n, e)
            if (rows2.size < 1000 && s <= dead[0] && w <= dead[1] && n >= dead[2] && e >= dead[3]) null else rows2.take(1000)
        }
        assertEquals(12, done2.size)
        assertTrue(cells[0] !in done2)
    }
}
