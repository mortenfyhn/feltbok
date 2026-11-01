package io.github.mortenfyhn.feltbok

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One registered Artsobservasjoner locality (from build_localities.py). We emit
 *  [lokalitet] (the bare name the import matches) at [lat]/[lon] (its canonical
 *  point, which disambiguates duplicate names). */
data class Locality(
    val id: String,
    val lokalitet: String,
    val hovedlokalitet: String,
    val kommune: String,
    val lat: Double,
    val lon: Double,
    val observers: Int, // distinct observers - a public-establishedness signal for the map
    val radius: Double, // the locality's map footprint radius in metres (0 = unknown)
    val polygon: List<DoubleArray> = emptyList(), // real footprint as [lat,lon] vertices, when it is an area
    val public: Boolean = true, // an allmenn (public) locality, vs one of the user's own
    val mine: Boolean = false, // the user's own custom locality (private to them; links by bare name)
    val isSuper: Boolean = false, // a superlocality (has sublocalities) - drawn in a deeper green
    val newLoc: Boolean = false,  // a brand-new spot the user just placed - exported WITH coords to mint it
) {
    /** [latMin, latMax, lonMin, lonMax] of the footprint, computed once (the map needs it
     *  every frame for culling and fading); null for point localities. */
    val polyBounds: DoubleArray? by lazy {
        if (polygon.isEmpty()) return@lazy null
        var laMin = 90.0; var laMax = -90.0; var loMin = 180.0; var loMax = -180.0
        for (v in polygon) {
            laMin = minOf(laMin, v[0]); laMax = maxOf(laMax, v[0])
            loMin = minOf(loMin, v[1]); loMax = maxOf(loMax, v[1])
        }
        doubleArrayOf(laMin, laMax, loMin, loMax)
    }

    /** [lat, lon] to anchor the map label on: a polygon's pole of inaccessibility (so the name
     *  sits on the shape even when concave, #135), else the locality's own point. Computed once. */
    val labelAnchor: DoubleArray by lazy {
        if (polygon.isEmpty()) doubleArrayOf(lat, lon) else poleOfInaccessibility(polygon)
    }

    /** [latMin, latMax, lonMin, lonMax] for viewport culling - the polygon extent, or the
     *  radius circle's box for point localities. Computed once (the trig was per-frame before). */
    val cullBounds: DoubleArray by lazy {
        polyBounds ?: run {
            val dLat = radius / 111_320.0
            val dLon = radius / (111_320.0 * cos(rad(lat)))
            doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
        }
    }
}

// ---- geometry ----

private fun rad(deg: Double) = deg * PI / 180

/** Great-circle distance in metres. */
fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = rad(lat2 - lat1)
    val dLon = rad(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Ray-casting point-in-polygon test. [polygon] holds [lat,lon] vertices (see [Locality.polygon]),
 *  with lat as y and lon as x. Boundary cases aren't special-cased - it only has to decide which
 *  locality a tap landed in. */
fun pointInPolygon(lat: Double, lon: Double, polygon: List<DoubleArray>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val latI = polygon[i][0]; val lonI = polygon[i][1]
        val latJ = polygon[j][0]; val lonJ = polygon[j][1]
        if ((latI > lat) != (latJ > lat) &&
            lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** Whether a point falls inside a locality's real footprint: its polygon, or its radius circle.
 *  Point localities (no polygon, radius 0) have no footprint, so the picker falls back to a
 *  touch-radius hit-test for those instead. */
fun localityContains(loc: Locality, lat: Double, lon: Double): Boolean =
    if (loc.polygon.isNotEmpty()) pointInPolygon(lat, lon, loc.polygon)
    else loc.radius > 0.0 && haversine(lat, lon, loc.lat, loc.lon) <= loc.radius

/** Signed great-circle distance (m) from [lat]/[lon] to a locality's footprint edge, negative when
 *  inside. Circle: centre distance minus radius. Polygon: nearest-edge distance, negated inside. A
 *  point locality (radius 0, no polygon) reduces to plain centre distance. Lets the picker rank by
 *  how far you are from a locality's *edge* rather than its centre - so a big locality you're inside
 *  (or nearer the edge of) beats a small one you're far outside (#155). */
fun distanceToFootprint(loc: Locality, lat: Double, lon: Double): Double {
    if (loc.polygon.isEmpty()) return haversine(lat, lon, loc.lat, loc.lon) - loc.radius
    // Local equirectangular projection (metres) centred on the query point, so the vertices become
    // planar and segDistSq gives real metres to the nearest edge.
    val cosLat = cos(rad(lat))
    var minSq = Double.MAX_VALUE
    var j = loc.polygon.size - 1
    for (i in loc.polygon.indices) {
        val ax = (loc.polygon[i][1] - lon) * 111_320.0 * cosLat; val ay = (loc.polygon[i][0] - lat) * 111_320.0
        val bx = (loc.polygon[j][1] - lon) * 111_320.0 * cosLat; val by = (loc.polygon[j][0] - lat) * 111_320.0
        minSq = minOf(minSq, segDistSq(0.0, 0.0, ax, ay, bx, by))
        j = i
    }
    val edge = sqrt(minSq)
    return if (pointInPolygon(lat, lon, loc.polygon)) -edge else edge
}

/** Footprint area in m². Circle: π·r². Polygon: shoelace over a local equirectangular projection.
 *  Point localities are 0. Used to pick the *smallest* locality you're inside as the default (#155). */
fun footprintArea(loc: Locality): Double {
    if (loc.polygon.isEmpty()) return PI * loc.radius * loc.radius
    val cosLat = cos(rad(loc.lat))
    var sum = 0.0
    var j = loc.polygon.size - 1
    for (i in loc.polygon.indices) {
        val xi = loc.polygon[i][1] * 111_320.0 * cosLat; val yi = loc.polygon[i][0] * 111_320.0
        val xj = loc.polygon[j][1] * 111_320.0 * cosLat; val yj = loc.polygon[j][0] * 111_320.0
        sum += xj * yi - xi * yj
        j = i
    }
    return abs(sum) / 2.0
}

/** Squared distance from point (px,py) to segment a->b, in planar coords. */
private fun segDistSq(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    var x = ax; var y = ay
    val dx = bx - ax; val dy = by - ay
    if (dx != 0.0 || dy != 0.0) {
        val t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
        if (t > 1.0) { x = bx; y = by } else if (t > 0.0) { x += dx * t; y += dy * t }
    }
    return (px - x) * (px - x) + (py - y) * (py - y)
}

/** Signed distance from (px,py) to the polygon edges (negative outside), in planar coords. */
private fun pointToPolygonDist(px: Double, py: Double, pts: Array<DoubleArray>): Double {
    var inside = false
    var minSq = Double.MAX_VALUE
    var j = pts.size - 1
    for (i in pts.indices) {
        val ax = pts[i][0]; val ay = pts[i][1]; val bx = pts[j][0]; val by = pts[j][1]
        if ((ay > py) != (by > py) && px < (bx - ax) * (py - ay) / (by - ay) + ax) inside = !inside
        minSq = minOf(minSq, segDistSq(px, py, ax, ay, bx, by))
        j = i
    }
    return (if (inside) 1.0 else -1.0) * sqrt(minSq)
}

/** Pole of inaccessibility: the interior point of [polygon] ([lat,lon] vertices) furthest from any
 *  edge - the natural spot to anchor an area's label so the name sits ON the shape, even for a
 *  concave/banana outline (#135). Mapbox's "polylabel": grid-subdivide, always refine the most
 *  promising cell. Returned as [lat, lon]. Worked in a lon-scaled planar space so a degree east and
 *  a degree north count about equally at this latitude; computed once per locality (it's lazy). */
fun poleOfInaccessibility(polygon: List<DoubleArray>): DoubleArray {
    var laMin = 90.0; var laMax = -90.0; var loMin = 180.0; var loMax = -180.0
    for (v in polygon) {
        laMin = minOf(laMin, v[0]); laMax = maxOf(laMax, v[0])
        loMin = minOf(loMin, v[1]); loMax = maxOf(loMax, v[1])
    }
    val k = cos(rad((laMin + laMax) / 2))         // lon scale at this latitude
    val cyMid = (laMin + laMax) / 2; val cxMid = (loMin + loMax) / 2 * k
    val w = (loMax - loMin) * k; val h = laMax - laMin
    val cellSize = minOf(w, h)
    if (polygon.size < 3 || cellSize <= 0.0) return doubleArrayOf(cyMid, cxMid / k)
    val pts = Array(polygon.size) { doubleArrayOf(polygon[it][1] * k, polygon[it][0]) }  // [x=lon*k, y=lat]

    // A cell as [x, y, half, dist, potential]; potential = dist + half*√2 bounds any point in it.
    fun cell(x: Double, y: Double, half: Double): DoubleArray {
        val d = pointToPolygonDist(x, y, pts)
        return doubleArrayOf(x, y, half, d, d + half * 1.4142135623730951)
    }
    val precision = cellSize / 100.0
    val half = cellSize / 2
    // Kept sorted by potential with the most promising cell last (common Kotlin has no PriorityQueue).
    val queue = ArrayList<DoubleArray>()
    fun push(c: DoubleArray) {
        val i = queue.binarySearch { it[4].compareTo(c[4]) }
        queue.add(if (i < 0) -i - 1 else i, c)
    }
    var x = loMin * k
    while (x < loMax * k) {
        var y = laMin
        while (y < laMax) { push(cell(x + half, y + half, half)); y += cellSize }
        x += cellSize
    }
    var best = cell(cxMid, cyMid, 0.0)                       // bbox centre as the initial best guess
    while (queue.isNotEmpty()) {
        val c = queue.removeAt(queue.lastIndex)
        if (c[3] > best[3]) best = c
        if (c[4] - best[3] <= precision) continue            // this cell can't beat the best by enough
        val q = c[2] / 2
        push(cell(c[0] - q, c[1] - q, q)); push(cell(c[0] + q, c[1] - q, q))
        push(cell(c[0] - q, c[1] + q, q)); push(cell(c[0] + q, c[1] + q, q))
    }
    return doubleArrayOf(best[1], best[0] / k)               // back to [lat, lon]
}
