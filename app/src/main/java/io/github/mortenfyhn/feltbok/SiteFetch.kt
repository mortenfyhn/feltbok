package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// Live fetch of public localities in the picker's visible box (#173 layer C), so a locality
// created on Artsobservasjoner since the bundle was harvested shows up without a release.

/** Widest view that gets looked up (~18 km on the ground at 63°N). Sparse areas load whole from this
 *  far out; dense ones run out of [LIVE_FETCH_BUDGET] and complete as you zoom in. */
const val LIVE_FETCH_MAX_M = 40_000.0

/** Requests per map settle. Dense areas truncate and need splitting, so this caps what one settle
 *  can cost (a truncated response is ~350 KB of JSON, ~60 KB gzipped on the wire); whatever is left
 *  stays unchecked for the next one. */
const val LIVE_FETCH_BUDGET = 8

/** Requests left for one settle, shared by all its [fetchCells] calls. */
class RequestBudget(var left: Int)

private const val CELL_M = 2_000.0

// The server rejects a box over 50 km per side in Web Mercator, so wider groups are split before
// asking (the padded view can reach 80 km).
private const val SERVER_MAX_M = 45_000.0

// The server truncates silently, and returns up to about twice MaxSites when it does; treat a
// response this long as truncated (same check as harvest_sites_mobil.py). The website's own map
// does the same with MaxSites=200.
private const val MAX_SITES = 1000

internal fun mercX(lon: Double) = lon * MERC_HALF / 180
internal fun mercY(lat: Double) = ln(tan((90 + lat) * PI / 360)) / (PI / 180) * MERC_HALF / 180

/** Widest view whose leftover cells are fetched in further rounds while the map stays still. A
 *  complete 10 km view of central Trondheim is ~30 requests (a ~5 km one 23 requests, 4.2 MB of
 *  JSON, ~0.7 MB gzipped), nearly all of it other people's private rows. */
const val LIVE_CONTINUE_MAX_M = 10_000.0

/** Whether a south/west/north/east box is at most [maxM] across in Web Mercator. */
fun fitsSiteQuery(s: Double, w: Double, n: Double, e: Double, maxM: Double = LIVE_FETCH_MAX_M) =
    mercX(e) - mercX(w) <= maxM && mercY(n) - mercY(s) <= maxM

private fun cellX(c: Long) = (c shr 32).toInt()
private fun cellY(c: Long) = c.toInt()

/** The fixed Web-Mercator grid cells covering a box, packed as (x shl 32 | y). A fixed grid lets the
 *  picker remember what it has checked regardless of how the map was panned onto it. */
fun cellsIn(s: Double, w: Double, n: Double, e: Double): List<Long> {
    val x0 = floor(mercX(w) / CELL_M).toInt(); val x1 = floor(mercX(e) / CELL_M).toInt()
    val y0 = floor(mercY(s) / CELL_M).toInt(); val y1 = floor(mercY(n) / CELL_M).toInt()
    return (x0..x1).flatMap { x -> (y0..y1).map { y -> (x.toLong() shl 32) or (y.toLong() and 0xffffffffL) } }
}

/** South/west/north/east of the smallest box covering [cells]. */
fun cellsBox(cells: Collection<Long>): DoubleArray {
    val xs = cells.map(::cellX); val ys = cells.map(::cellY)
    return doubleArrayOf(
        mercToLat(ys.min() * CELL_M), mercToLon(xs.min() * CELL_M),
        mercToLat((ys.max() + 1) * CELL_M), mercToLon((xs.max() + 1) * CELL_M),
    )
}

/** The cells to look up for a view, in priority order: the view itself, then a margin of half a view
 *  on each side as four strips, so a short pan shows localities that are already loaded. */
fun fetchGroups(s: Double, w: Double, n: Double, e: Double): List<List<Long>> {
    val view = cellsIn(s, w, n, e)
    val vx = view.map(::cellX).let { it.min()..it.max() }; val vy = view.map(::cellY).let { it.min()..it.max() }
    val dLat = (n - s) / 2; val dLon = (e - w) / 2
    val margin = cellsIn(s - dLat, w - dLon, n + dLat, e + dLon).filter { cellX(it) !in vx || cellY(it) !in vy }
    val strips = margin.groupBy {
        when {
            cellY(it) > vy.last -> 0
            cellY(it) < vy.first -> 1
            cellX(it) < vx.first -> 2
            else -> 3
        }
    }
    return listOf(view) + strips.values
}

/** [cells] split into up to four groups at the middle of their range. Each group is strictly smaller
 *  when there is more than one cell, so repeated splitting ends at single cells. */
internal fun splitCells(cells: List<Long>): Collection<List<Long>> {
    val xs = cells.map(::cellX); val ys = cells.map(::cellY)
    val xm = Math.floorDiv(xs.min() + xs.max(), 2); val ym = Math.floorDiv(ys.min() + ys.max(), 2)
    return cells.groupBy { (cellX(it) > xm) to (cellY(it) > ym) }.values
}

internal fun jsonRows(json: String): List<JSONObject> = JSONArray(json).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

/** ByBoundingBox rows for the box, or null on any failure (offline, endpoint changed). */
private suspend fun fetchSites(s: Double, w: Double, n: Double, e: Double): List<JSONObject>? {
    val base = Country.publicSitesUrl ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val q = "MinX=$w&MinY=$s&MaxX=$e&MaxY=$n&MaxSites=$MAX_SITES&IncludePublicSites=true"
            val c = URL("$base?$q").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-CSRF", "1")
            c.setRequestProperty("User-Agent", "Feltbok/${BuildConfig.VERSION_NAME}")
            try { jsonRows(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        }.getOrNull()
    }
}

/** What [fetchCells] got: the cells covered completely, all rows seen, and the groups the budget
 *  ran out before, to resume from. Their parents were already fetched (and truncated), so resuming
 *  from these instead of from the remaining cells doesn't ask for the same boxes again. */
data class Fetched(val done: List<Long>, val rows: List<JSONObject>, val pending: List<List<Long>>)

/** Rows for [cells], split into quarters for as long as the server truncates, spending [budget].
 *  A truncated response's rows are kept too: they're real localities, and all we get if the budget
 *  runs out before its parts. A failed request leaves its cells out (neither done nor pending); a
 *  single cell that still truncates is accepted as it is, as the harvest does. */
suspend fun fetchCells(
    cells: List<Long>,
    budget: RequestBudget,
    fetch: suspend (s: Double, w: Double, n: Double, e: Double) -> List<JSONObject>? = ::fetchSites,
): Fetched {
    val xs = cells.map(::cellX); val ys = cells.map(::cellY)
    val tooBig = (xs.max() - xs.min() + 1) * CELL_M > SERVER_MAX_M || (ys.max() - ys.min() + 1) * CELL_M > SERVER_MAX_M
    if (!tooBig) {
        if (budget.left <= 0) return Fetched(emptyList(), emptyList(), listOf(cells))
        budget.left--
    }
    val rows = if (tooBig) emptyList() else {
        val (s, w, n, e) = cellsBox(cells)
        fetch(s, w, n, e) ?: return Fetched(emptyList(), emptyList(), emptyList())
    }
    if (!tooBig && (rows.size < MAX_SITES || cells.size == 1)) return Fetched(cells, rows, emptyList())
    val parts = splitCells(cells).map { fetchCells(it, budget, fetch) }
    return Fetched(parts.flatMap { it.done }, rows + parts.flatMap { it.rows }, parts.flatMap { it.pending })
}

/** Public localities from ByBoundingBox rows. Private rows are other people's own sites and are
 *  dropped. The parent name is resolved against the rows themselves first, then [knownName] (the
 *  already-loaded list), since a parent can lie outside the box. Mirrors build_sites.py. */
fun parsePublicSites(rows: List<JSONObject>, knownName: (String) -> String?): List<Locality> {
    val names = rows.associate { it.optLong("id").toString() to it.optString("name") }
    val supers = rows.mapNotNull { r -> r.optLong("parentSiteId").takeIf { it != 0L }?.toString() }.toSet()
    // A site on the edge between two requested boxes comes back in both.
    return rows.filter { !it.optBoolean("isPrivate", true) }.distinctBy { it.optLong("id") }.mapNotNull { r ->
        val lat = r.optDouble("latitude", Double.NaN); val lon = r.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
        val id = r.optLong("id").toString()
        val parent = r.optLong("parentSiteId").takeIf { it != 0L }?.toString()
        Locality(
            id = id,
            lokalitet = r.optString("name"),
            hovedlokalitet = parent?.let { names[it] ?: knownName(it) } ?: "",
            kommune = r.optString("municipalityName"),
            lat = lat, lon = lon,
            observers = 0, radius = r.optDouble("accuracy", 0.0),
            polygon = if (r.optBoolean("isPolygon")) lonLatRingToVertices(r.optString("polygonCoordinates")) else emptyList(),
            isSuper = id in supers,
        )
    }
}
