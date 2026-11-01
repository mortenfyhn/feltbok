package io.github.mortenfyhn.feltbok

// ---- CSV loading (bundled asset, or a dev override pushed via adb) ----

/** Split one CSV line, honouring double-quoted fields. */
fun parseCsvLine(line: String): List<String> {
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

private fun csvRows(text: String?): List<List<String>> =
    text.orEmpty().lineSequence()
        .filter { it.isNotBlank() }
        .drop(1) // header
        .map { parseCsvLine(it) }
        .toList()

/** A bundled CSV's rows, empty when this build doesn't ship it. */
private fun readData(storage: Storage, name: String): List<List<String>> = csvRows(storage.readAsset(name))

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
 *  Two sources, private preferred: the in-app sync writes [MY_LOCALITIES] (always app-writable);
 *  `just push-data` seeds a pushed copy (dev convenience). Once you've synced in-app, that file
 *  wins - online is the source of truth. */
private fun readMyLocalities(storage: Storage): List<List<String>> =
    csvRows(storage.read(MY_LOCALITIES) ?: storage.readPushed(MY_LOCALITIES))

private const val MY_LOCALITIES = "my-localities.csv"

/** Like toDoubleOrNull but without its per-call regex screen - parseDouble straight, null on
 *  the rare malformed value. Called ~35k times at launch, so the regex screen was costly. */
private fun fastDouble(s: String): Double? =
    if (s.isEmpty()) null else try { s.toDouble() } catch (e: NumberFormatException) { null }

/** Columns: id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine,super */
fun parseLocalityRow(c: List<String>): Locality? {
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
 *  customs from `my-localities.csv` (present only once they've synced). [versionCode] names the
 *  live-locality file (see [liveLocalitiesName]).
 *  `localities.csv` is optional — the Sweden flavor ships none (its import is coordinate-driven,
 *  so spots are minted on the map) — so a missing file yields an empty list, not a crash. */
fun loadLocalities(storage: Storage, versionCode: Int): List<Locality> {
    val base = (readData(storage, "localities.csv") + readMyLocalities(storage)).mapNotNull(::parseLocalityRow)
    return base + withoutKnownIds(readLiveLocalities(storage, versionCode).mapNotNull(::parseLocalityRow), base)
}

/** Public localities fetched live in the picker (#173), kept so they survive a restart. One file per
 *  app version: a release brings a re-harvested bundle, so rows fetched against the old one are
 *  dropped, including any since deleted, renamed or made private. Still-new ones are fetched again
 *  when viewed. */
private fun liveLocalitiesName(versionCode: Int) = "live-localities-$versionCode.csv"

private fun readLiveLocalities(storage: Storage, versionCode: Int): List<List<String>> {
    val name = liveLocalitiesName(versionCode)
    storage.list().filter { it.startsWith("live-localities") && it != name }.forEach(storage::delete)
    return csvRows(storage.read(name))
}

fun appendLiveLocalities(storage: Storage, versionCode: Int, sites: List<Locality>) {
    val name = liveLocalitiesName(versionCode)
    val sb = StringBuilder()
    if (!storage.exists(name)) sb.append("id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine,super\n")
    for (s in sites) sb.append(liveLocalityRow(s))
    storage.append(name, sb.toString())
}

fun liveLocalityRow(s: Locality) = localityCsvRow(s, listOf("1", "0", if (s.isSuper) "1" else "0"))

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

private fun csvField(s: String): String =
    if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** Persist the synced privates to the `my-localities.csv` the app loads at start, so they survive
 *  restarts. Polygons are written back as WKT for [parsePolygon]. */
fun saveMyLocalities(storage: Storage, sites: List<Locality>) {
    val sb = StringBuilder()
    sb.append("id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine\n")
    for (s in sites) sb.append(localityCsvRow(s, listOf("0", "1")))
    storage.write(MY_LOCALITIES, sb.toString())
}

/** One CSV row up to `geometry`, then [flags] (public, mine[, super]). Polygons go back as WKT for [parsePolygon]. */
private fun localityCsvRow(s: Locality, flags: List<String>): String {
    val geom = if (s.polygon.isEmpty()) ""
    else "POLYGON((" + s.polygon.joinToString(", ") { "${it[1]} ${it[0]}" } + "))"
    return (listOf(
        s.id, s.lokalitet, s.hovedlokalitet, s.kommune, "",
        s.lat.toString(), s.lon.toString(), "0", "0",
        s.radius.toString(), geom,
    ) + flags).joinToString(",", postfix = "\n") { csvField(it) }
}

/** Columns: latin,norsk,svensk,status,count. `latin` is the stable key (unique; the vernacular
 *  names have homonyms and may be blank). status = Rødlista/Rödlista or alien-list category, blank
 *  if neither; count = the national report count, log-scaled into the search's commonness ranking. */
fun loadSpecies(storage: Storage): List<Species> =
    readData(storage, "species.csv").mapNotNull { c ->
        if (c.isEmpty() || c[0].isBlank()) {
            null
        } else {
            Species(
                latin = c[0],
                norsk = c.getOrElse(1) { "" },
                svensk = c.getOrElse(2) { "" },
                status = c.getOrElse(3) { "" },
                count = c.getOrElse(4) { "" }.toIntOrNull() ?: 0,
            )
        }
    }

/** "ub." (ubestemt/unidentified) entries (#162) - e.g. "ub. makrell-/rødnebbterne" for when you
 *  can narrow a sighting to a genus or a small group of similar species but not the exact one.
 *  Columns: latin,norsk (see scripts/build_ubestemt.py). Norway-only asset: optional, like
 *  [loadSpeciesMonths] - the Sweden build simply has no such file, so this degrades to empty. */
fun loadUbestemt(storage: Storage): List<Species> =
    readData(storage, "ubestemt.csv").mapNotNull { c ->
        if (c.isEmpty() || c[0].isBlank()) null else Species(latin = c[0], norsk = c.getOrElse(1) { "" })
    }

/** Per-species monthly report counts (latin -> 12 ints), for season-aware ranking ([SeasonalFrequency]).
 *  Optional asset: empty if absent, so search just falls back to all-time frequency. */
fun loadSpeciesMonths(storage: Storage): Map<String, IntArray> =
    readData(storage, "species_months.csv")
        .mapNotNull { c ->
            if (c.size < 13 || c[0].isBlank()) null
            else c[0] to IntArray(12) { c.getOrElse(it + 1) { "" }.toIntOrNull() ?: 0 }
        }.toMap()

/** Per-grid-cell species report counts (cellKey -> latin -> count), for locality-aware ranking
 *  ([ContextualFrequency]). Rows are latS,lonW,latin,count; cellKey packs latS*100+lonW. Optional
 *  asset: empty if absent, so the region term simply drops out. */
fun loadSpeciesRegions(storage: Storage): Map<Int, Map<String, Int>> {
    val out = HashMap<Int, HashMap<String, Int>>()
    readData(storage, "species_regions.csv").forEach { c ->
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
