package io.github.mortenfyhn.feltbok

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

// Artskart's rule for point localities: a point acts as a 200 m disk, shrunk to half the distance to
// the nearest other point when that is closer than 400 m, so neighbouring disks just touch. Without
// it a point has no footprint, so any area you stand inside beats a point a few metres away.
private const val POINT_RADIUS_M = 200.0
private const val LAT_SPAN_DEG = 2 * POINT_RADIUS_M / 110_000.0 // >= 400 m everywhere (1° lat >= 110.5 km)

private fun isPoint(loc: Locality) = loc.polygon.isEmpty() && loc.radius <= 0.0

/** The effective radius (m) of each point locality in [all], by index; 0 for circles and polygons.
 *  Only other points shrink it. Points at the same spot get 0 and behave as plain points. */
fun pointRadii(all: List<Locality>): DoubleArray {
    val radii = DoubleArray(all.size)
    // Sorted by latitude through a primitive LongArray: (lat in µdeg) << 32 | index. Boxed sortedBy
    // was most of the run time.
    val keys = LongArray(all.count(::isPoint))
    var n = 0
    for (i in all.indices) if (isPoint(all[i])) keys[n++] = ((all[i].lat + 90) * 1e6).toLong() shl 32 or i.toLong()
    keys.sort()
    val points = IntArray(keys.size) { (keys[it] and 0xffffffffL).toInt() }
    val lat = DoubleArray(points.size) { all[points[it]].lat }
    val lon = DoubleArray(points.size) { all[points[it]].lon }
    val nearest = DoubleArray(points.size) { 2 * POINT_RADIUS_M }
    // Sweep north through the points sorted by latitude: only those less than 400 m further north can
    // be neighbours, and a cheap longitude check skips most of them before the haversine. Primitive
    // arrays, as the inner loop runs about a million times for the bundled localities.
    for (a in points.indices) {
        val lonSpan = LAT_SPAN_DEG / cos((abs(lat[a]) + LAT_SPAN_DEG) * PI / 180)
        var b = a + 1
        while (b < points.size && lat[b] - lat[a] < LAT_SPAN_DEG) {
            if (abs(lon[b] - lon[a]) < lonSpan) {
                val d = haversine(lat[a], lon[a], lat[b], lon[b])
                nearest[a] = min(nearest[a], d)
                nearest[b] = min(nearest[b], d)
            }
            b++
        }
    }
    for (a in points.indices) radii[points[a]] = nearest[a] / 2
    return radii
}

/** Default locality for a position, given [radii] from [pointRadii] for the same [all]. If you're
 *  inside one or more footprints, the *smallest* containing one wins (the most specific - matching
 *  how a tap resolves nesting, #126); otherwise the one whose footprint *edge* is nearest. So a big
 *  locality you're inside beats a small one you're outside, yet a small locality nested inside a big
 *  one still wins when you're inside both (#155). */
fun defaultLocality(all: List<Locality>, radii: DoubleArray, lat: Double, lon: Double): Locality? {
    var inside: Locality? = null
    var insideArea = Double.MAX_VALUE
    for (i in all.indices) {
        val loc = all[i]; val r = radii[i]
        val contains = if (r > 0.0) haversine(lat, lon, loc.lat, loc.lon) <= r else localityContains(loc, lat, lon)
        if (!contains) continue
        val area = if (r > 0.0) PI * r * r else footprintArea(loc)
        if (area < insideArea) { inside = loc; insideArea = area }
    }
    if (inside != null) return inside
    val best = all.indices.minByOrNull { i ->
        val r = radii[i]
        if (r > 0.0) haversine(lat, lon, all[i].lat, all[i].lon) - r else distanceToFootprint(all[i], lat, lon)
    }
    return best?.let { all[it] }
}
