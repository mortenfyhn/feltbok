package io.github.mortenfyhn.feltbok

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
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
const val MAX_SITES = 1000

// Web Mercator (EPSG:3857, metres) <-> WGS84. Also used for the GeoJSON the Swedish sites API returns.
internal const val MERC_HALF = 20037508.34
fun mercToLon(x: Double) = x / MERC_HALF * 180.0
fun mercToLat(y: Double) = (2 * atan(exp(y / MERC_HALF * PI)) - PI / 2) * 180 / PI
fun mercX(lon: Double) = lon * MERC_HALF / 180
fun mercY(lat: Double) = ln(tan((90 + lat) * PI / 360)) / (PI / 180) * MERC_HALF / 180

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
fun splitCells(cells: List<Long>): Collection<List<Long>> {
    val xs = cells.map(::cellX); val ys = cells.map(::cellY)
    val xm = (xs.min() + xs.max()).floorDiv(2); val ym = (ys.min() + ys.max()).floorDiv(2)
    return cells.groupBy { (cellX(it) > xm) to (cellY(it) > ym) }.values
}

/** What [fetchCells] got: the cells covered completely, all rows seen, and the groups the budget
 *  ran out before, to resume from. Their parents were already fetched (and truncated), so resuming
 *  from these instead of from the remaining cells doesn't ask for the same boxes again. */
data class Fetched(val done: List<Long>, val rows: List<SiteRow>, val pending: List<List<Long>>)

/** Rows for [cells], split into quarters for as long as the server truncates, spending [budget].
 *  A truncated response's rows are kept too: they're real localities, and all we get if the budget
 *  runs out before its parts. A failed request leaves its cells out (neither done nor pending); a
 *  single cell that still truncates is accepted as it is, as the harvest does. */
suspend fun fetchCells(
    cells: List<Long>,
    budget: RequestBudget,
    fetch: suspend (s: Double, w: Double, n: Double, e: Double) -> List<SiteRow>?,
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

/** [live] without the localities whose id is already in [known]. A re-harvested bundle (or rows
 *  fetched before the bundle had finished loading) would otherwise show them twice. */
fun withoutKnownIds(live: List<Locality>, known: List<Locality>): List<Locality> {
    val ids = known.mapTo(HashSet()) { it.id }
    return live.filter { it.id !in ids }
}

/** One ByBoundingBox row, with only the fields we use. [lat]/[lon] are NaN when missing; such rows
 *  still count towards the truncation check, so they are kept until [parsePublicSites]. */
data class SiteRow(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val isPrivate: Boolean,
    val radius: Double,
    val municipality: String,
    val parentId: String?,
    val polygon: List<DoubleArray>, // [lat,lon] vertices, empty for a point or circle
)

/** Public localities from ByBoundingBox rows. Private rows are other people's own sites and are
 *  dropped. The parent name is resolved against the rows themselves first, then [knownName] (the
 *  already-loaded list), since a parent can lie outside the box. Mirrors build_sites.py. */
fun parsePublicSites(rows: List<SiteRow>, knownName: (String) -> String?): List<Locality> {
    val names = rows.associate { it.id to it.name }
    val supers = rows.mapNotNull { it.parentId }.toSet()
    // A site on the edge between two requested boxes comes back in both.
    return rows.filter { !it.isPrivate && !it.lat.isNaN() && !it.lon.isNaN() }.distinctBy { it.id }.map { r ->
        Locality(
            id = r.id,
            lokalitet = r.name,
            hovedlokalitet = r.parentId?.let { names[it] ?: knownName(it) } ?: "",
            kommune = r.municipality,
            lat = r.lat, lon = r.lon,
            observers = 0, radius = r.radius,
            polygon = r.polygon,
            isSuper = r.id in supers,
        )
    }
}
