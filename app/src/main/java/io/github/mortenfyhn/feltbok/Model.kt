package io.github.mortenfyhn.feltbok

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Valid Artsobservasjoner option values for birds (Fugl), exactly as the import
 *  expects them. Common ones first so they're quick to reach in the field. */
object Options {
    val ages = listOf(
        "Egg", "Pulli", "Adult",
        "1K", "1K+", "2K", "2K+", "2K-", "3K", "3K+", "3K-", "4K", "4K+", "4K-",
        "5K", "5K+", "5K-", "6K", "6K+", "6K-", "7K", "7K+", "7K-",
    )

    // The everyday non-breeding activities, surfaced first so the long list below
    // doesn't have to be scrolled for the common case.
    private val commonActivities = listOf(
        "Rastende", "Stasjonær", "Overflygende", "Næringssøkende", "Trekkende",
        "Sang/spill, ikke hekking", "Lokkelyd, øvrige lyder", "Ved fôring",
        "Revir, ikke hekking", "Permanent revir",
    )

    // The full Fugl activity list, in the template/website order.
    private val allActivities = listOf(
        "Reir med egg eller unger", "Reir, unger hørt", "Rugende", "Mat til unger",
        "Bar ekskrementpose", "Reir i bruk", "Besøker bebodd reir",
        "Unger utenfor reir, ikke utvokste", "Brukt reir", "Eggeskall",
        "Avledningsmanøver", "Mislykket hekking", "Reirbygging", "Rugeflekker",
        "Engstelig adferd, indikasjon på hekking", "Reirbesøk?",
        "Paring/kurtise på mulig hekkeplass", "Permanent revir",
        "Par i passende hekkebiotop", "Sang/spill i hekketid og passende hekkebiotop",
        "Observasjon i hekketid, passende biotop", "Rastende", "Stasjonær",
        "Overflygende", "Næringssøkende", "Ved fôring", "Sang/spill, ikke hekking",
        "Lokkelyd, øvrige lyder", "Revir, ikke hekking", "Ringmerket",
        "Individmerket (kontroll)", "Trekkforsøk", "Trekkende", "Trekkende mot N",
        "Trekkende mot NØ", "Trekkende mot Ø", "Trekkende mot SØ", "Trekkende mot S",
        "Trekkende mot SV", "Trekkende mot V", "Trekkende mot NV", "Syk",
        "Død - kollisjon med kraftledning", "Død - kollisjon med vindturbin",
        "Død - kollisjon med vindu", "Død - kollisjon med fyr", "Død - kollisjon med fly",
        "Død - kollisjon med gjerde", "Drept av elektrokusjon (strømslag)",
        "Drept av olje", "Trafikkdrept", "Garndød", "Skadet av fiskeredskap",
        "Drept av predator", "Død av sykdom/sult", "Skutt/avlivet",
        "Død - ukjent dødsårsak", "Ferske spor", "Eldre spor", "Fersk møkk", "Eldre møkk",
    )
    val activities = commonActivities + allActivities.filterNot { it in commonActivities }
    val sexes = listOf("Hann", "Hunn", "Hunnfarget", "I par")

    /** Nøyaktighet written for every row - the locality's own coordinate is exact
     *  enough that the import snaps to the registered locality. */
    const val accuracy = "100 m"
}

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
            val dLon = radius / (111_320.0 * cos(Math.toRadians(lat)))
            doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
        }
    }
}

data class Species(val norsk: String, val latin: String, val status: String = "", val count: Int = 0)

const val UNKNOWN_COUNT = -1 // Note.count sentinel: unknown number of individuals

data class Note(
    val id: Long, // creation time in ms - the stable key (never changes)
    val time: Long = id, // observation start (date+time) - editable; defaults to id
    val endTime: Long? = null, // observation end; null = a single time point (Til = Fra on export)
    val species: String,
    val latin: String,
    val count: Int, // UNKNOWN_COUNT (-1) = unknown number of individuals (-> blank Antall)
    val age: String,
    val activity: String,
    val sex: String,
    val publicComment: String,
    val privateComment: String,
    val locName: String, // short locality name, for display
    val locFull: String, // qualified Lokalitetsnavn, for the import
    val lat: Double,
    val lon: Double,
    val newLoc: Boolean = false, // a brand-new spot: export with coordinates so the import mints it
    val locRadius: Int = 0, // chosen radius in metres for a new spot (-> Nøyaktighet)
    val uncertain: Boolean = false, // uncertain species determination (-> "Usikker artsbestemming")
    // Kommune the obs belongs to, stamped at save for the per-kommune export grouping. A new spot
    // resolves it from the nearest registry locality at creation; blank on notes saved before this
    // existed (grouping then falls back to the locFull / nearest-locality lookup).
    val kommune: String = "",
)

// ---- distance ----

/** Great-circle distance in metres. */
fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

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
    val k = cos(Math.toRadians((laMin + laMax) / 2))         // lon scale at this latitude
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
    val queue = java.util.PriorityQueue<DoubleArray>(8) { a, b -> b[4].compareTo(a[4]) }
    var x = loMin * k
    while (x < loMax * k) {
        var y = laMin
        while (y < laMax) { queue.add(cell(x + half, y + half, half)); y += cellSize }
        x += cellSize
    }
    var best = cell(cxMid, cyMid, 0.0)                       // bbox centre as the initial best guess
    while (queue.isNotEmpty()) {
        val c = queue.poll()
        if (c[3] > best[3]) best = c
        if (c[4] - best[3] <= precision) continue            // this cell can't beat the best by enough
        val q = c[2] / 2
        queue.add(cell(c[0] - q, c[1] - q, q)); queue.add(cell(c[0] + q, c[1] - q, q))
        queue.add(cell(c[0] - q, c[1] + q, q)); queue.add(cell(c[0] + q, c[1] + q, q))
    }
    return doubleArrayOf(best[1], best[0] / k)               // back to [lat, lon]
}

// ---- species search ----

/** Fold Norwegian/diacritic letters so typing plain ASCII still matches. The aa/oe collapse also
 *  accepts the digraph spellings people use without å/ø ("graagaas" -> grågås, "roedstrupe" -> rød…);
 *  no real Norwegian bird name carries a literal aa/oe, so collapsing them is safe. */
fun fold(s: String) = s.lowercase()
    .replace("æ", "ae").replace("ø", "o").replace("å", "a")
    .replace("ô", "o").replace("é", "e").replace("è", "e").replace("ü", "u")
    .replace("aa", "a").replace("oe", "o")

/** Fold a search query and drop its spaces, so "f m" == "fm". */
fun foldQuery(query: String) = fold(query).filterNot { it.isWhitespace() }

/**
 * Rank an already-folded [q] (see [foldQuery]) against an already-folded [t]
 * (see [fold]). Lower is better, or null for no match:
 *  0 = prefix (name starts with the query)
 *  1 = name's first letter matches and the rest is a subsequence - the "initials"
 *      case, so "pf" -> Pilfink beats a mid-word match like Lappfiskand
 *  2 = the query is a contiguous substring elsewhere in the name
 *  3 = the query is a scattered subsequence elsewhere
 * Pre-folding the names once keeps this hot per-keystroke loop allocation-free.
 */
fun fuzzyRank(q: String, t: String): Int? {
    if (q.isEmpty()) return 0
    if (t.startsWith(q)) return 0
    var qi = 0
    for (c in t) {
        if (c == q[qi]) qi++
        if (qi == q.length) break
    }
    val isSubseq = qi == q.length
    val anchored = q[0] == t.firstOrNull()
    return when {
        anchored && isSubseq -> 1
        t.contains(q) -> 2
        isSubseq -> 3
        else -> null
    }
}

// ---- date/time formatting ----

private val NB = Locale("nb", "NO")

// Norwegian's abbreviated months carry a trailing dot ("jun."); we render them dotless everywhere.
private val NB_SYMBOLS = DateFormatSymbols(NB).apply {
    shortMonths = shortMonths.map { it.trimEnd('.') }.toTypedArray()
}
private fun nbFormat(pattern: String) = SimpleDateFormat(pattern, NB).apply { dateFormatSymbols = NB_SYMBOLS }

fun displayTime(ms: Long): String = nbFormat("d. MMM, HH:mm").format(Date(ms))
fun shortTime(ms: Long): String = nbFormat("HH:mm").format(Date(ms))

/** The editor's one-line time summary: a single point, or "from – to" for a range. */
fun displayTimeRange(start: Long, end: Long?): String {
    val fmt = nbFormat("d. MMM HH:mm")
    return if (end == null) fmt.format(Date(start)) else "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
}

fun exportDate(ms: Long): String = nbFormat("dd.MM.yyyy").format(Date(ms))

/** Friendlier date for the editor (export keeps the strict dd.MM.yyyy). */
fun displayDate(ms: Long): String = nbFormat("d. MMM yyyy").format(Date(ms))
fun exportTime(ms: Long): String = nbFormat("HH:mm").format(Date(ms))

// ---- day grouping (the notes list's per-day section headers) ----

/** A calendar day's notes for the list, with a header label relative to today. */
data class DayGroup(val label: String, val notes: List<Note>)

private val weekdayFmt = DateTimeFormatter.ofPattern("EEE", NB)
private val monthFmt = DateTimeFormatter.ofPattern("MMM", NB)

/** Label a day relative to [today]: "I dag", "I går", else an abbreviated date like "Søn 8. jun"
 *  (with year appended when it isn't [today]'s). The locale abbreviations carry trailing periods
 *  ("søn.", "jun.") we don't want, so trim them and capitalise the weekday by hand. */
private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> Strings.Notes.today
    today.minusDays(1) -> Strings.Notes.yesterday
    else -> buildString {
        append(day.format(weekdayFmt).trimEnd('.').replaceFirstChar { it.uppercase() })
        append(" ${day.dayOfMonth}. ")
        append(day.format(monthFmt).trimEnd('.'))
        if (day.year != today.year) append(" ${day.year}")
    }
}

/** Group notes into one section per calendar day, newest day first and newest observation first
 *  within each day — ordered by observation time, not entry order, so a note dated in the past
 *  drops into its own day instead of jumping to the top. Pure ([today]/[zone] injected) for tests. */
fun groupNotesByDay(
    notes: List<Note>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<DayGroup> =
    notes.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
        .toSortedMap(reverseOrder())
        .map { (day, ns) -> DayGroup(dayLabel(day, today), ns.sortedByDescending { it.time }) }

// ---- CSV loading (asset, or an external override pushed via adb) ----

/** Split one CSV line, honouring double-quoted fields. */
private fun parseCsvLine(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

private fun readData(ctx: Context, name: String): List<List<String>> {
    val ext = File(ctx.getExternalFilesDir(null), name)
    val text = if (ext.exists()) ext.readText()
    else ctx.assets.open(name).bufferedReader().use { it.readText() }
    return text.lineSequence()
        .filter { it.isNotBlank() }
        .drop(1) // header
        .map { parseCsvLine(it) }
        .toList()
}

/** Parse a "POLYGON((lon lat, ...))" WKT into [lat,lon] vertices (empty if not a polygon).
 *  Hand-scanned rather than regex'd: the regex version cost ~9 ms per polygon (Android's
 *  java.util.regex is slow), which dominated app launch. Inner-ring structure is ignored
 *  (all vertices flattened into one outline), as before. */
private fun parsePolygon(wkt: String): List<DoubleArray> {
    if (!wkt.startsWith("POLYGON")) return emptyList()
    val pts = ArrayList<DoubleArray>(128)
    var i = 0; val n = wkt.length; var lon = Double.NaN
    while (i < n) {
        val c = wkt[i]
        if (c == '-' || c == '.' || c in '0'..'9') {
            val start = i++
            while (i < n && (wkt[i] in '0'..'9' || wkt[i] == '.')) i++
            val v = wkt.substring(start, i).toDouble()
            if (lon.isNaN()) lon = v else { pts.add(doubleArrayOf(v, lon)); lon = Double.NaN }  // WKT is lon lat
        } else i++
    }
    return pts
}

/** The user's own private localities, never bundled/committed (so a shared APK is public-only).
 *  Two sources, internal preferred: the in-app sync writes [myLocalitiesFile] (internal storage,
 *  always app-writable); `just push-data` seeds the external files dir (dev convenience). Once
 *  you've synced in-app, that file wins - online is the source of truth. */
private fun readMyLocalities(ctx: Context): List<List<String>> {
    val internal = myLocalitiesFile(ctx)
    val external = File(ctx.getExternalFilesDir(null), "my-localities.csv")
    val f = if (internal.exists()) internal else external
    if (!f.exists()) return emptyList()
    return f.readText().lineSequence().filter { it.isNotBlank() }.drop(1).map { parseCsvLine(it) }.toList()
}

private fun myLocalitiesFile(ctx: Context) = File(ctx.filesDir, "my-localities.csv")

/** Like toDoubleOrNull but without its per-call regex screen - parseDouble straight, null on
 *  the rare malformed value. Called ~35k times at launch, so the regex screen was costly. */
private fun fastDouble(s: String): Double? =
    if (s.isEmpty()) null else try { s.toDouble() } catch (e: NumberFormatException) { null }

/** Columns: id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine,super */
private fun parseLocalityRow(c: List<String>): Locality? {
    if (c.size < 7) return null
    val lat = fastDouble(c[5]) ?: return null
    val lon = fastDouble(c[6]) ?: return null
    val obs = c.getOrElse(8) { "" }.toIntOrNull() ?: 0
    // radius onward sit one column later in legacy CSVs that still carry a text `fullname` at
    // index 9 (older bundles + a not-yet-resynced my-localities.csv). Detect by content, not
    // width: the standard layout has the numeric radius at 9, a legacy fullname is non-numeric
    // there - a width test would mistake the newer `super` column for the legacy shift.
    val b = if (c.size >= 14 && fastDouble(c.getOrElse(9) { "" }) == null) 10 else 9
    val radius = fastDouble(c.getOrElse(b) { "" }) ?: 0.0
    val poly = parsePolygon(c.getOrElse(b + 1) { "" })
    val public = c.getOrElse(b + 2) { "1" } != "0"
    val mine = c.getOrElse(b + 3) { "0" } == "1"
    val isSuper = c.getOrElse(b + 4) { "0" } == "1"
    // Show public (allmenn) localities and the user's own customs; drop anything else.
    if (!public && !mine) return null
    return Locality(c[0], c[1], c[2], c[3], lat, lon, obs, radius, poly, public = public, mine = mine, isSuper = isSuper)
}

/** Public localities from the bundled (or pushed) `localities.csv`, plus the user's own
 *  customs from the external-only `my-localities.csv` (present only on the maintainer's device). */
fun loadLocalities(ctx: Context): List<Locality> =
    (readData(ctx, "localities.csv") + readMyLocalities(ctx))
        .mapNotNull(::parseLocalityRow)

// ---- "my localities" sync (from the mobile API's /core/Sites/ByUser) ----

/** What a sync changed versus the previously cached set, for the "Henta …" confirmation. */
data class SyncDiff(val total: Int, val changed: Int, val firstSync: Boolean)

// The fields whose change we treat as a meaningful edit to an existing locality (name, place,
// footprint). id is the identity, so it is excluded; polygon is flattened to compare by value.
private fun localitySig(l: Locality) =
    listOf(l.lokalitet, l.lat, l.lon, l.radius, l.polygon.flatMap { it.toList() })

/** Diff old vs new "mine" localities by id: count added + removed + content-changed. */
fun diffMySites(old: List<Locality>, new: List<Locality>): SyncDiff {
    val oldById = old.associateBy { it.id }
    val newById = new.associateBy { it.id }
    val added = newById.keys - oldById.keys
    val removed = oldById.keys - newById.keys
    val modified = (oldById.keys intersect newById.keys)
        .count { localitySig(oldById.getValue(it)) != localitySig(newById.getValue(it)) }
    return SyncDiff(total = new.size, changed = added.size + removed.size + modified, firstSync = old.isEmpty())
}

/** Map the mobile API's accumulated `{data:[...]}` rows to the user's own private localities.
 *  Coordinates are already WGS84; only `isPrivate` sites go to the mine-file (public ones are
 *  already in the bundled set). */
fun parseMySites(json: String): List<Locality> {
    val arr = JSONObject(json).optJSONArray("data") ?: return emptyList()
    val out = ArrayList<Locality>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        if (!o.optBoolean("isPrivate")) continue
        val lat = o.optDouble("latitude", Double.NaN); val lon = o.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) continue
        val poly = if (o.optBoolean("isPolygon")) lonLatRingToVertices(o.optString("polygonCoordinates"))
        else emptyList()
        out.add(Locality(
            id = o.optLong("id").toString(),
            lokalitet = o.optString("name"),
            hovedlokalitet = "",
            kommune = o.optString("municipalityName"),
            lat = lat, lon = lon,
            observers = 0, radius = o.optDouble("accuracy", 0.0),
            polygon = poly, public = false, mine = true,
        ))
    }
    return out
}

/** "[[lon,lat],...]" (WGS84 JSON) -> [lat,lon] vertices, matching our polygon convention. */
private fun lonLatRingToVertices(s: String): List<DoubleArray> {
    if (s.isBlank()) return emptyList()
    return try {
        val a = JSONArray(s)
        ArrayList<DoubleArray>(a.length()).apply {
            for (i in 0 until a.length()) {
                val p = a.getJSONArray(i)
                add(doubleArrayOf(p.getDouble(1), p.getDouble(0)))
            }
        }
    } catch (e: Exception) { emptyList() }
}

private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** Persist the synced privates to the same external `my-localities.csv` the app loads at start,
 *  so they survive restarts. Polygons are written back as WKT for [parsePolygon]. */
fun saveMyLocalities(ctx: Context, sites: List<Locality>) {
    val sb = StringBuilder()
    sb.append("id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine\n")
    for (s in sites) {
        val geom = if (s.polygon.isEmpty()) ""
        else "POLYGON((" + s.polygon.joinToString(", ") { "${it[1]} ${it[0]}" } + "))"
        sb.append(listOf(
            s.id, s.lokalitet, s.hovedlokalitet, s.kommune, "",
            s.lat.toString(), s.lon.toString(), "0", "0",
            s.radius.toString(), geom, "0", "1",
        ).joinToString(",", postfix = "\n") { csvField(it) })
    }
    myLocalitiesFile(ctx).writeText(sb.toString())   // internal storage: always app-writable
}

/** Columns: norsk,latin,status (Rødlista 2021 or Fremmedartslista 2023 category, blank if neither) */
fun loadSpecies(ctx: Context): List<Species> =
    readData(ctx, "species.csv").mapNotNull { c ->
        if (c.isEmpty() || c[0].isBlank()) {
            null
        } else {
            // count (Norway-wide observations) is optional - old/overridden CSVs may lack it.
            Species(c[0], c.getOrElse(1) { "" }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }.toIntOrNull() ?: 0)
        }
    }

/** Per-species monthly report counts (latin -> 12 ints), for season-aware ranking ([SeasonalFrequency]).
 *  Optional asset: empty if absent, so search just falls back to all-time frequency. */
fun loadSpeciesMonths(ctx: Context): Map<String, IntArray> =
    runCatching { readData(ctx, "species_months.csv") }.getOrDefault(emptyList())
        .mapNotNull { c ->
            if (c.size < 13 || c[0].isBlank()) null
            else c[0] to IntArray(12) { c.getOrElse(it + 1) { "" }.toIntOrNull() ?: 0 }
        }.toMap()

/** Per-grid-cell species report counts (cellKey -> latin -> count), for locality-aware ranking
 *  ([ContextualFrequency]). Rows are latS,lonW,latin,count; cellKey packs latS*100+lonW. Optional
 *  asset: empty if absent, so the region term simply drops out. */
fun loadSpeciesRegions(ctx: Context): Map<Int, Map<String, Int>> {
    val out = HashMap<Int, HashMap<String, Int>>()
    runCatching { readData(ctx, "species_regions.csv") }.getOrDefault(emptyList()).forEach { c ->
        if (c.size >= 4) {
            val latS = c[0].toIntOrNull()
            val lonW = c[1].toIntOrNull()
            val cnt = c[3].toIntOrNull()
            if (latS != null && lonW != null && cnt != null && c[2].isNotBlank()) {
                out.getOrPut(latS * 100 + lonW) { HashMap() }[c[2]] = cnt
            }
        }
    }
    return out
}

// ---- note persistence (a JSON file in app storage) ----

private fun notesFile(ctx: Context) = File(ctx.filesDir, "notes.json")

/**
 * Two observations entered in the same millisecond share an `id` (it's the creation time).
 * `id` is the list's stable key and the handle for edit/delete/select, so a collision both
 * crashes LazyColumn and makes those actions ambiguous. Heal by nudging duplicates forward
 * a millisecond until unique - `id` is internal only (not shown or exported), so this is safe.
 */
internal fun withUniqueIds(notes: List<Note>): List<Note> {
    val seen = HashSet<Long>()
    return notes.map { n ->
        var id = n.id
        while (!seen.add(id)) id++
        if (id == n.id) n else n.copy(id = id)
    }
}

/** The per-field JSON mapping for a [Note], kept as pure functions (no Context) so a round-trip
 *  test can catch a field that's saved but not loaded, or vice versa - a silent data-loss bug on
 *  the next launch. endTime is the one optional field: omitted when null, restored as null. */
fun noteToJson(n: Note): JSONObject = JSONObject().apply {
    put("id", n.id); put("time", n.time); put("species", n.species); put("latin", n.latin)
    n.endTime?.let { put("endTime", it) }
    put("count", n.count); put("age", n.age); put("activity", n.activity)
    put("sex", n.sex); put("publicComment", n.publicComment)
    put("privateComment", n.privateComment)
    put("locName", n.locName); put("locFull", n.locFull)
    put("lat", n.lat); put("lon", n.lon)
    put("newLoc", n.newLoc); put("locRadius", n.locRadius); put("uncertain", n.uncertain)
    if (n.kommune.isNotBlank()) put("kommune", n.kommune)
}

fun noteFromJson(o: JSONObject): Note = Note(
    id = o.getLong("id"),
    time = o.optLong("time", o.getLong("id")),
    endTime = if (o.has("endTime")) o.getLong("endTime") else null,
    species = o.getString("species"),
    latin = o.optString("latin"),
    count = o.getInt("count"),
    age = o.optString("age"),
    activity = o.optString("activity"),
    sex = o.optString("sex"),
    publicComment = o.optString("publicComment"),
    privateComment = o.optString("privateComment"),
    locName = o.optString("locName"),
    locFull = o.optString("locFull"),
    lat = o.getDouble("lat"),
    lon = o.getDouble("lon"),
    newLoc = o.optBoolean("newLoc"),
    locRadius = o.optInt("locRadius"),
    uncertain = o.optBoolean("uncertain"),
    kommune = o.optString("kommune"),
)

fun loadNotes(ctx: Context): List<Note> {
    val f = notesFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching {
        val arr = JSONArray(f.readText())
        withUniqueIds((0 until arr.length()).map { noteFromJson(arr.getJSONObject(it)) })
    }.getOrDefault(emptyList())
}

fun saveNotes(ctx: Context, notes: List<Note>) {
    val arr = JSONArray()
    notes.forEach { arr.put(noteToJson(it)) }
    notesFile(ctx).writeText(arr.toString())
}

/**
 * Best-effort mirror of the export into the public Downloads folder, refreshed on every persist.
 * notes.json lives in private internal storage, which a non-debuggable release seals off (no
 * run-as, no pull) - so a UI crash like #85 left field observations unreachable until a rebuild.
 * A submit-ready TSV in Downloads is recoverable with any file manager, and survives even an
 * uninstall. Q+ only: MediaStore needs no permission there; older devices would need a runtime
 * storage grant, not worth it. Never throws - a failed backup must not break saving.
 */
fun backupExport(ctx: Context, notes: List<Note>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching {
        val name = "feltbok-sikkerhetskopi.tsv"
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Reuse the existing mirror (matched by its distinctive name) so saves overwrite one
        // file instead of accumulating copies; otherwise mint a fresh one under Downloads/Feltbok.
        val existing = resolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val uri = existing?.let { ContentUris.withAppendedId(collection, it) }
            ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/tab-separated-values")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Feltbok")
                },
            ) ?: return@runCatching
        resolver.openOutputStream(uri, "wt")?.use { it.write(exportTsv(notes).toByteArray()) }
    }
}

// ---- per-species use scores: a pick count that fades with time (see UseScore.kt), so recent
//      picks rank above once-frequent-now-stale ones. Replaces the old recent.json + int uses.json. ----

private fun usesFile(ctx: Context) = File(ctx.filesDir, "uses.json")

fun loadUses(ctx: Context, now: Long = System.currentTimeMillis()): Map<String, UseEntry> {
    val f = usesFile(ctx)
    if (!f.exists()) return emptyMap()
    return runCatching {
        val o = JSONObject(f.readText())
        o.keys().asSequence().associateWith { k ->
            when (val v = o.get(k)) {
                is JSONObject -> UseEntry(v.getDouble("s"), v.getLong("t"))
                // Legacy format was a bare count, no timestamp. Seed it a day back: clear of the pin
                // window (so migrated regulars aren't mistaken for the current batch), and the tiny
                // uniform decay doesn't change their relative order.
                else -> UseEntry((v as Number).toDouble(), now - 24L * 60 * 60 * 1000)
            }
        }
    }.getOrDefault(emptyMap())
}

fun saveUses(ctx: Context, uses: Map<String, UseEntry>) {
    val o = JSONObject()
    uses.forEach { (k, e) -> o.put(k, JSONObject().put("s", e.score).put("t", e.lastTouched)) }
    usesFile(ctx).writeText(o.toString())
}

// ---- per-activity use counts, so your most-used activities rank to the top ----

private fun loadCounts(ctx: Context, file: String): Map<String, Int> {
    val f = File(ctx.filesDir, file)
    if (!f.exists()) return emptyMap()
    return runCatching {
        val o = JSONObject(f.readText())
        o.keys().asSequence().associateWith { o.getInt(it) }
    }.getOrDefault(emptyMap())
}

private fun saveCounts(ctx: Context, file: String, counts: Map<String, Int>) {
    val o = JSONObject()
    counts.forEach { (k, v) -> o.put(k, v) }
    File(ctx.filesDir, file).writeText(o.toString())
}

fun loadActUses(ctx: Context) = loadCounts(ctx, "act_uses.json")
fun saveActUses(ctx: Context, uses: Map<String, Int>) = saveCounts(ctx, "act_uses.json", uses)

// ---- export (v2.20 paste format: bare name only, no coords) ----

private val EXPORT_COLS = listOf(
    "Artsnavn", "Antall", "Alder", "Kjønn", "Aktivitet", "Lokalitetsnavn", "Nord", "Øst",
    "Nøyaktighet", "Fra dato", "Fra klokkeslett", "Til dato", "Til klokkeslett",
    "Kommentar (synlig for alle)", "Privat kommentar (kun synlig for deg selv)",
    // The registered template header is misspelt "artsbestemming" (not "-bestemmelse").
    // Paste-import matches columns by header name, so the misspelling must be reproduced
    // verbatim or a flagged row fails validation ("Usikker artsbestemming" = col 40 of the
    // v2.20 Fugl template; checkbox cells accept «X»/«ja»/«1», so "Ja" is fine).
    "Usikker artsbestemming",
)

fun exportTsv(notes: List<Note>): String {
    // Emit the *bare* Lokalitetsnavn with no coordinates. Paste-import behaviour
    // (tested against the live v2.20 site, see docs/artsobs-import.md):
    //  - a comma-qualified name ("Lok, Hovedlok, Kommune, Fylke") HARD-FAILS validation.
    //  - name / name+coords each validate but MINT a private duplicate - paste never
    //    links to a public locality. Only manual selection on the site links to public.
    // So we emit the bare name and let the user scope the import form to the kommune to
    // disambiguate, fixing the rest by hand. Nord/Øst/Nøyaktighet are left blank.
    val rows = notes.sortedBy { it.time }.map { n ->
        val d = exportDate(n.time); val t = exportTime(n.time)
        // Til defaults to Fra; an explicit endTime makes it a range (may cross midnight).
        val end = n.endTime ?: n.time
        val dEnd = exportDate(end); val tEnd = exportTime(end)
        val loc = n.locName.ifBlank { n.locFull }
        // A brand-new spot is exported WITH coordinates (+ its radius as Nøyaktighet), which
        // mints a new custom locality on import; registry localities stay name-only.
        val nord = if (n.newLoc) String.format(Locale.US, "%.6f", n.lat) else ""
        val ost = if (n.newLoc) String.format(Locale.US, "%.6f", n.lon) else ""
        val noy = if (n.newLoc) "${n.locRadius} m" else ""
        listOf(
            n.species, if (n.count == UNKNOWN_COUNT) "" else n.count.toString(), n.age, n.sex, n.activity, loc,
            nord, ost, noy, d, t, dEnd, tEnd, n.publicComment, n.privateComment,
            if (n.uncertain) "Ja" else "",
        ).joinToString("\t") { cell ->
            // A tab or newline in a free-text comment would split the row and desync
            // every following column on paste-import; flatten them to spaces.
            cell.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
    }
    return (listOf(EXPORT_COLS.joinToString("\t")) + rows).joinToString("\n")
}

/** A kommune's worth of notes, for a self-contained per-kommune paste block. */
data class ExportGroup(val kommune: String, val notes: List<Note>)

/** The registered fullname is "Lok[, Hovedlok…], Kommune, Fylke", so the kommune is the
 *  second-to-last comma field. Blank for an unqualified or empty name (e.g. a brand-new spot). */
private fun kommuneFromFullname(locFull: String): String {
    val parts = locFull.split(",")
    return if (parts.size >= 3) parts[parts.size - 2].trim() else ""
}

/** Kommune of the registry locality nearest [lat]/[lon], for stamping a brand-new spot with the
 *  kommune around it. Only localities that carry a kommune count (a new spot is itself a
 *  kommune-less private locality, so it can't be its own answer). Blank until localities load. */
fun nearestKommune(lat: Double, lon: Double, localities: List<Locality>): String =
    localities.filter { it.kommune.isNotBlank() }
        .minByOrNull { haversine(it.lat, it.lon, lat, lon) }?.kommune ?: ""

/**
 * Group notes by kommune so each block can be pasted with the import form scoped to that
 * kommune. The form takes one kommune scope per paste, so a multi-kommune batch must be
 * split (see docs/artsobs-import.md).
 *
 * The kommune is stamped on each note at save time, so grouping is instant and needs no
 * registry. Notes saved before that field existed fall back to their registered fullname, then
 * (only for an unqualified brand-new spot) to the nearest registry locality. A spot whose
 * kommune still can't be resolved falls into a trailing blank group.
 */
fun groupNotesByKommune(notes: List<Note>, localities: List<Locality>): List<ExportGroup> {
    fun kommuneFor(n: Note): String =
        n.kommune
            .ifBlank { kommuneFromFullname(n.locFull) }
            .ifBlank { nearestKommune(n.lat, n.lon, localities) }
    return notes.groupBy { kommuneFor(it) }
        .map { (kommune, ns) -> ExportGroup(kommune, ns) }
        // Sort by kommune name, but keep the unresolved (blank) group last.
        .sortedWith(compareBy({ it.kommune.isBlank() }, { it.kommune }))
}
