package com.appobs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
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
    val fullname: String,         // qualified "Lok, Hovedlok, Kommune, Fylke" - what the import matches
    val observers: Int,           // distinct observers - a public-establishedness signal for the map
    val radius: Double,           // the locality's map footprint radius in metres (0 = unknown)
    val polygon: List<DoubleArray> = emptyList(),  // real footprint as [lat,lon] vertices, when it is an area
    val public: Boolean = true,   // an allmenn (public) locality, vs one of the user's own
    val mine: Boolean = false,    // the user's own custom locality (private to them; links by bare name)
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
}

data class Species(val norsk: String, val latin: String)

data class Note(
    val id: Long,                 // creation time in ms - the stable key (never changes)
    val time: Long = id,          // the observation time (date+time) - editable; defaults to id
    val species: String,
    val latin: String,
    val count: Int,
    val age: String,
    val activity: String,
    val sex: String,
    val publicComment: String,
    val privateComment: String,
    val locName: String,          // short locality name, for display
    val locFull: String,          // qualified Lokalitetsnavn, for the import
    val lat: Double,
    val lon: Double,
    val newLoc: Boolean = false,  // a brand-new spot: export with coordinates so the import mints it
    val locRadius: Int = 0,       // chosen radius in metres for a new spot (-> Nøyaktighet)
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

// ---- species search ----

/** Fold Norwegian/diacritic letters so typing plain ASCII still matches. */
fun fold(s: String) = s.lowercase()
    .replace("æ", "ae").replace("ø", "o").replace("å", "a")
    .replace("ô", "o").replace("é", "e").replace("è", "e").replace("ü", "u")

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

/** Convenience that folds both sides; used in tests. */
fun fuzzyScore(query: String, target: String): Int? = fuzzyRank(foldQuery(query), fold(target))

// ---- date/time formatting ----

private val NB = Locale("nb", "NO")
fun displayTime(ms: Long): String = SimpleDateFormat("d. MMM, HH:mm", NB).format(Date(ms))
fun shortTime(ms: Long): String = SimpleDateFormat("HH:mm", NB).format(Date(ms))
fun exportDate(ms: Long): String = SimpleDateFormat("dd.MM.yyyy", NB).format(Date(ms))
fun exportTime(ms: Long): String = SimpleDateFormat("HH:mm", NB).format(Date(ms))

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

private val WKT_NUM = Regex("-?\\d+(?:\\.\\d+)?")

/** Parse a "POLYGON((lon lat, ...))" WKT into [lat,lon] vertices (empty if not a polygon). */
private fun parsePolygon(wkt: String): List<DoubleArray> {
    if (!wkt.startsWith("POLYGON")) return emptyList()
    val n = WKT_NUM.findAll(wkt).map { it.value.toDouble() }.toList()
    val pts = ArrayList<DoubleArray>(n.size / 2)
    var i = 0
    while (i + 1 < n.size) { pts.add(doubleArrayOf(n[i + 1], n[i])); i += 2 }  // WKT is lon lat
    return pts
}

/** Read an external-only override file (no bundled-asset fallback); empty if absent. Used for
 *  `my-localities.csv` - the maintainer's own privates, pushed to their device but never
 *  bundled/committed, so a shared APK ships public localities only. */
private fun readExternal(ctx: Context, name: String): List<List<String>> {
    val ext = File(ctx.getExternalFilesDir(null), name)
    if (!ext.exists()) return emptyList()
    return ext.readText().lineSequence().filter { it.isNotBlank() }.drop(1).map { parseCsvLine(it) }.toList()
}

/** Columns: id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,fullname,radius,geometry,public,mine */
private fun parseLocalityRow(c: List<String>): Locality? {
    if (c.size < 7) return null
    val lat = c[5].toDoubleOrNull() ?: return null
    val lon = c[6].toDoubleOrNull() ?: return null
    val full = c.getOrElse(9) { "" }.ifBlank { c[1] }   // fall back to bare name pre-fullname
    val obs = c.getOrElse(8) { "" }.toIntOrNull() ?: 0
    val radius = c.getOrElse(10) { "" }.toDoubleOrNull() ?: 0.0
    val poly = parsePolygon(c.getOrElse(11) { "" })
    val public = c.getOrElse(12) { "1" } != "0"
    val mine = c.getOrElse(13) { "0" } == "1"
    // Show public (allmenn) localities and the user's own customs; drop anything else.
    if (!public && !mine) return null
    return Locality(c[0], c[1], c[2], c[3], lat, lon, full, obs, radius, poly, public = public, mine = mine)
}

/** Public localities from the bundled (or pushed) `localities.csv`, plus the user's own
 *  customs from the external-only `my-localities.csv` (present only on the maintainer's device). */
fun loadLocalities(ctx: Context): List<Locality> =
    (readData(ctx, "localities.csv") + readExternal(ctx, "my-localities.csv"))
        .mapNotNull(::parseLocalityRow)

/** Columns: norsk,latin */
fun loadSpecies(ctx: Context): List<Species> =
    readData(ctx, "species.csv").mapNotNull { c ->
        if (c.isEmpty() || c[0].isBlank()) null else Species(c[0], c.getOrElse(1) { "" })
    }

// ---- note persistence (a JSON file in app storage) ----

private fun notesFile(ctx: Context) = File(ctx.filesDir, "notes.json")

fun loadNotes(ctx: Context): List<Note> {
    val f = notesFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(
                id = o.getLong("id"),
                time = o.optLong("time", o.getLong("id")),
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
            )
        }
    }.getOrDefault(emptyList())
}

fun saveNotes(ctx: Context, notes: List<Note>) {
    val arr = JSONArray()
    notes.forEach { n ->
        arr.put(JSONObject().apply {
            put("id", n.id); put("time", n.time); put("species", n.species); put("latin", n.latin)
            put("count", n.count); put("age", n.age); put("activity", n.activity)
            put("sex", n.sex); put("publicComment", n.publicComment)
            put("privateComment", n.privateComment)
            put("locName", n.locName); put("locFull", n.locFull)
            put("lat", n.lat); put("lon", n.lon)
            put("newLoc", n.newLoc); put("locRadius", n.locRadius)
        })
    }
    notesFile(ctx).writeText(arr.toString())
}

// ---- recent species (most-recent first), persisted so the quick list survives restarts ----

private fun recentFile(ctx: Context) = File(ctx.filesDir, "recent.json")

fun loadRecent(ctx: Context): List<String> {
    val f = recentFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching {
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}

fun saveRecent(ctx: Context, names: List<String>) {
    recentFile(ctx).writeText(JSONArray(names.toList()).toString())
}

// ---- per-species use counts, so your own regulars rank to the top ----

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

fun loadUses(ctx: Context) = loadCounts(ctx, "uses.json")
fun saveUses(ctx: Context, uses: Map<String, Int>) = saveCounts(ctx, "uses.json", uses)
fun loadActUses(ctx: Context) = loadCounts(ctx, "act_uses.json")
fun saveActUses(ctx: Context, uses: Map<String, Int>) = saveCounts(ctx, "act_uses.json", uses)

// ---- export (v2.20 paste format: bare name only, no coords) ----

private val EXPORT_COLS = listOf(
    "Artsnavn", "Antall", "Alder", "Kjønn", "Aktivitet", "Lokalitetsnavn", "Nord", "Øst",
    "Nøyaktighet", "Fra dato", "Fra klokkeslett", "Til dato", "Til klokkeslett",
    "Kommentar (synlig for alle)", "Privat kommentar (kun synlig for deg selv)",
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
        val loc = n.locName.ifBlank { n.locFull }
        // A brand-new spot is exported WITH coordinates (+ its radius as Nøyaktighet), which
        // mints a new custom locality on import; registry localities stay name-only.
        val nord = if (n.newLoc) String.format(Locale.US, "%.6f", n.lat) else ""
        val ost = if (n.newLoc) String.format(Locale.US, "%.6f", n.lon) else ""
        val noy = if (n.newLoc) "${n.locRadius} m" else ""
        listOf(
            n.species, n.count.toString(), n.age, n.sex, n.activity, loc,
            nord, ost, noy, d, t, d, t, n.publicComment, n.privateComment,
        ).joinToString("\t") { cell ->
            // A tab or newline in a free-text comment would split the row and desync
            // every following column on paste-import; flatten them to spaces.
            cell.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
    }
    return (listOf(EXPORT_COLS.joinToString("\t")) + rows).joinToString("\n")
}
