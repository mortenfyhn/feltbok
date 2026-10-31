package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.runBlocking
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
        val sites = parsePublicSites(siteRows(json)) { if (it == "99") "Known" else null }.associateBy { it.id }
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
        for (s in parsePublicSites(siteRows(json)) { null }) {
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
                val lat = s + (n - s) * (i % 10 + 0.5) / 10; val lon = w + (e - w) * (i / 10 + 0.5) / 16
                SiteRow("$c-$i", "", lat, lon, isPrivate = false, radius = 0.0, municipality = "", parentId = null, polygon = emptyList())
            }
        }
        fun inBox(s: Double, w: Double, n: Double, e: Double) =
            sites.filter { it.lat in s..n && it.lon in w..e }

        val (done, rows) = fetchCells(cells, RequestBudget(100)) { s, w, n, e -> inBox(s, w, n, e).take(1000) }
        assertEquals("every cell complete", cells.toSet(), done.toSet())
        assertEquals("every site found", sites.size, rows.distinctBy { it.id }.size)

        // The quarter holding the first cell fails (offline): only its cells stay unchecked.
        val dead = cellsBox(listOf(cells[0]))
        val (done2, _) = fetchCells(cells, RequestBudget(100)) { s, w, n, e ->
            val rows2 = inBox(s, w, n, e)
            if (rows2.size < 1000 && s <= dead[0] && w <= dead[1] && n >= dead[2] && e >= dead[3]) null else rows2.take(1000)
        }
        assertEquals(12, done2.size)
        assertTrue(cells[0] !in done2)

        // With budget for three requests: the truncated whole and two quarters. Those two quarters'
        // cells are complete, the rest wait for the next settle, and the truncated rows still count.
        var requests = 0
        val (done3, rows3) = fetchCells(cells, RequestBudget(3)) { s, w, n, e -> requests++; inBox(s, w, n, e).take(1000) }
        assertEquals(3, requests)
        assertEquals(8, done3.size)
        assertEquals("truncated 1000 + two quarters of 600", 2200, rows3.size)

        // Resuming from what was left asks only for the two remaining quarters, not the whole again.
        requests = 0
        val pending = fetchCells(cells, RequestBudget(3)) { s, w, n, e -> inBox(s, w, n, e).take(1000) }.pending
        val resumed = pending.map { g -> fetchCells(g, RequestBudget(100)) { s, w, n, e -> requests++; inBox(s, w, n, e).take(1000) } }
        assertEquals(2, requests)
        assertEquals(cells.toSet() - done3.toSet(), resumed.flatMap { it.done }.toSet())
    }

    @Test
    fun fetchCellsSplitsGroupsTooWideForTheServerWithoutAsking() = runBlocking {
        // A 60 km strip (the padded margin can reach 80 km): the server would reject it, so it is
        // split first, and only boxes it accepts are sent and charged to the budget.
        val (x0, y0) = cellsIn(63.3, 8.0, 63.3, 8.0).single().let { (it shr 32).toInt() to it.toInt() }
        val strip = (x0 until x0 + 30).map { x -> (x.toLong() shl 32) or (y0.toLong() and 0xffffffffL) }
        val budget = RequestBudget(100)
        val (done, _) = fetchCells(strip, budget) { _, w, _, e ->
            assertTrue(mercX(e) - mercX(w) < 50_000)
            emptyList()
        }
        assertEquals(strip.toSet(), done.toSet())
        assertEquals(98, budget.left)
    }

    @Test
    fun fetchGroupsPutsTheViewFirstAndCoversTheMarginOnce() {
        val (s, w, n, e) = doubleArrayOf(63.85, 8.50, 63.88, 8.60)
        val groups = fetchGroups(s, w, n, e)
        assertEquals(cellsIn(s, w, n, e), groups.first())
        val all = groups.flatten()
        assertEquals("no cell in two groups", all.size, all.toSet().size)
        assertEquals(cellsIn(s - 0.015, w - 0.05, n + 0.015, e + 0.05).toSet(), all.toSet())
    }
}
