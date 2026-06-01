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
) {
    val context: String get() = listOf(hovedlokalitet, kommune).filter { it.isNotBlank() }.joinToString(", ")
}

data class Species(val norsk: String, val latin: String)

data class Note(
    val id: Long,                 // creation time in ms - stable key and the entry's timestamp
    val species: String,
    val latin: String,
    val count: Int,
    val age: String,
    val activity: String,
    val sex: String,
    val publicComment: String,
    val privateComment: String,
    val locName: String,          // bare Lokalitetsnavn
    val lat: Double,
    val lon: Double,
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
private fun fold(s: String) = s.lowercase()
    .replace("æ", "ae").replace("ø", "o").replace("å", "a")
    .replace("ô", "o").replace("é", "e").replace("è", "e").replace("ü", "u")

/**
 * Fuzzy match a query against a name. Returns a rank (lower is better) or null
 * for no match: 0 = prefix, 1 = substring, 2 = subsequence (letters in order
 * but not adjacent, so "rvt" matches "Rødvingetrost"). Spaces in the query are
 * ignored. Caller keeps the list's frequency order within an equal rank.
 */
fun fuzzyScore(query: String, target: String): Int? {
    val q = fold(query).filterNot { it.isWhitespace() }
    if (q.isEmpty()) return 0
    val t = fold(target)
    val idx = t.indexOf(q)
    if (idx == 0) return 0
    if (idx > 0) return 1
    var qi = 0
    for (c in t) {
        if (c == q[qi]) qi++
        if (qi == q.length) return 2
    }
    return null
}

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

/** Columns: id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count */
fun loadLocalities(ctx: Context): List<Locality> =
    readData(ctx, "localities.csv").mapNotNull { c ->
        if (c.size < 7) return@mapNotNull null
        val lat = c[5].toDoubleOrNull() ?: return@mapNotNull null
        val lon = c[6].toDoubleOrNull() ?: return@mapNotNull null
        Locality(c[0], c[1], c[2], c[3], lat, lon)
    }

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
                species = o.getString("species"),
                latin = o.optString("latin"),
                count = o.getInt("count"),
                age = o.optString("age"),
                activity = o.optString("activity"),
                sex = o.optString("sex"),
                publicComment = o.optString("publicComment"),
                privateComment = o.optString("privateComment"),
                locName = o.optString("locName"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
            )
        }
    }.getOrDefault(emptyList())
}

fun saveNotes(ctx: Context, notes: List<Note>) {
    val arr = JSONArray()
    notes.forEach { n ->
        arr.put(JSONObject().apply {
            put("id", n.id); put("species", n.species); put("latin", n.latin)
            put("count", n.count); put("age", n.age); put("activity", n.activity)
            put("sex", n.sex); put("publicComment", n.publicComment)
            put("privateComment", n.privateComment); put("locName", n.locName)
            put("lat", n.lat); put("lon", n.lon)
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

// ---- export (v2.20 paste format: bare name only, no coords) ----

private val EXPORT_COLS = listOf(
    "Artsnavn", "Antall", "Alder", "Kjønn", "Aktivitet", "Lokalitetsnavn", "Nord", "Øst",
    "Nøyaktighet", "Fra dato", "Fra klokkeslett", "Til dato", "Til klokkeslett",
    "Kommentar (synlig for alle)", "Privat kommentar (kun synlig for deg selv)",
)

fun exportTsv(notes: List<Note>): String {
    // Bare locality name, no coordinates: paste links the name to the public
    // locality, while including coords would mint a custom one (see
    // docs/artsobs-import.md). Nord/Øst/Nøyaktighet are left blank.
    val rows = notes.sortedBy { it.id }.map { n ->
        val d = exportDate(n.id); val t = exportTime(n.id)
        listOf(
            n.species, n.count.toString(), n.age, n.sex, n.activity, n.locName,
            "", "", "", d, t, d, t, n.publicComment, n.privateComment,
        ).joinToString("\t")
    }
    return (listOf(EXPORT_COLS.joinToString("\t")) + rows).joinToString("\n")
}
