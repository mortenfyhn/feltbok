package io.github.mortenfyhn.feltbok

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// Per-country option vocabularies, export format, geography and service URLs now live in Country.kt.

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

// ---- date/time formatting ----

// Display locale for weekday/month names, from the flavor (nb-NO for Norway, sv-SE for Sweden).
private val NB = Country.displayLocale

// The abbreviated months carry a trailing dot ("jun."); we render them dotless everywhere.
private val NB_SYMBOLS = DateFormatSymbols(NB).apply {
    shortMonths = shortMonths.map { it.trimEnd('.') }.toTypedArray()
}
private fun nbFormat(pattern: String) = SimpleDateFormat(pattern, NB).apply { dateFormatSymbols = NB_SYMBOLS }

fun shortTime(ms: Long): String = nbFormat("HH:mm").format(Date(ms))

/** The editor's one-line time summary: a single point, or "from – to" for a range. When the
 *  time-of-day is unspecified only the date shows (no HH:mm), matching how it exports. */
fun displayTimeRange(start: Long, end: Long?, timeUnknown: Boolean = false): String {
    val fmt = nbFormat(if (timeUnknown) "d. MMM" else "d. MMM HH:mm")
    return if (end == null) fmt.format(Date(start)) else "${fmt.format(Date(start))} – ${fmt.format(Date(end))}"
}

// Compact forms of age/sex for the list-row preview, so two near-identical observations are
// tellable apart at a glance (which is the chicks, which the parents). The row builds the preview
// itself (it bolds the thin sex symbol), so these stay as small reusable formatters. Both flavors'
// vocabularies are handled here (Norway capitalised, Sweden lower-case) so the preview reads "ad ♂"
// rather than "adult hane" on the Sweden build (#155).
fun shortAge(age: String): String = when (age) {
    "Adult" -> "Ad"
    "adult" -> "ad"
    "Pulli" -> "Pu"
    "pulli" -> "pu"
    "Egg" -> "Eg"
    "ägg" -> "äg"
    else -> age // the K-ages (1K, 2K+, …) are already short
}

// The trailing ︎ (VS15) forces text presentation, so ♂/♀ render from the regular text font
// instead of the emoji font — without it they come out as tall colour emoji that inflate the row.
fun sexSymbol(sex: String): String = when (sex) {
    "Hann", "Hane" -> "♂︎"
    "Hunn", "Hona" -> "♀︎"
    "Hunnfarget", "Honfärgad" -> "(♀︎)"
    "I par" -> "♂︎♀︎"
    else -> sex
}

// Export date/time use the country's format with a fixed locale — these patterns are purely
// numeric (no month names), so the locale doesn't affect the output, only the pattern does.
fun exportDate(ms: Long): String = SimpleDateFormat(Country.exportDateFmt, Locale.US).format(Date(ms))
fun exportTime(ms: Long): String = SimpleDateFormat(Country.exportTimeFmt, Locale.US).format(Date(ms))

/** Friendlier date for the editor (export keeps the strict numeric format). */
fun displayDate(ms: Long): String = nbFormat("d. MMM yyyy").format(Date(ms))

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
internal fun parseCsvLine(line: String): List<String> {
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
internal fun parseLocalityRow(c: List<String>): Locality? {
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
 *  customs from the external-only `my-localities.csv` (present only on the maintainer's device).
 *  `localities.csv` is optional — the Sweden flavor ships none (its import is coordinate-driven,
 *  so spots are minted on the map) — so a missing file yields an empty list, not a crash. */
fun loadLocalities(ctx: Context): List<Locality> {
    val base = (runCatching { readData(ctx, "localities.csv") }.getOrDefault(emptyList()) + readMyLocalities(ctx))
        .mapNotNull(::parseLocalityRow)
    return base + withoutKnownIds(readLiveLocalities(ctx).mapNotNull(::parseLocalityRow), base)
}

/** Public localities fetched live in the picker (#173), kept so they survive a restart. One file per
 *  app version: a release brings a re-harvested bundle, so rows fetched against the old one are
 *  dropped, including any since deleted, renamed or made private. Still-new ones are fetched again
 *  when viewed. */
private fun liveLocalitiesFile(ctx: Context) = File(ctx.filesDir, "live-localities-${BuildConfig.VERSION_CODE}.csv")

private fun readLiveLocalities(ctx: Context): List<List<String>> {
    val f = liveLocalitiesFile(ctx)
    ctx.filesDir.listFiles { o -> o.name.startsWith("live-localities") && o != f }?.forEach { it.delete() }
    if (!f.exists()) return emptyList()
    return f.readText().lineSequence().filter { it.isNotBlank() }.drop(1).map { parseCsvLine(it) }.toList()
}

fun appendLiveLocalities(ctx: Context, sites: List<Locality>) {
    val f = liveLocalitiesFile(ctx)
    val sb = StringBuilder()
    if (!f.exists()) sb.append("id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine,super\n")
    for (s in sites) sb.append(liveLocalityRow(s))
    f.appendText(sb.toString())
}

internal fun liveLocalityRow(s: Locality) = localityCsvRow(s, listOf("1", "0", if (s.isSuper) "1" else "0"))

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

/** Persist the synced privates to the same external `my-localities.csv` the app loads at start,
 *  so they survive restarts. Polygons are written back as WKT for [parsePolygon]. */
fun saveMyLocalities(ctx: Context, sites: List<Locality>) {
    val sb = StringBuilder()
    sb.append("id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine\n")
    for (s in sites) sb.append(localityCsvRow(s, listOf("0", "1")))
    myLocalitiesFile(ctx).writeText(sb.toString())   // internal storage: always app-writable
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
fun loadSpecies(ctx: Context): List<Species> =
    readData(ctx, "species.csv").mapNotNull { c ->
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
fun loadUbestemt(ctx: Context): List<Species> =
    runCatching { readData(ctx, "ubestemt.csv") }.getOrDefault(emptyList()).mapNotNull { c ->
        if (c.isEmpty() || c[0].isBlank()) null else Species(latin = c[0], norsk = c.getOrElse(1) { "" })
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

/** Write to a sibling temp file, fsync, then rename over [f]. A plain writeText truncates first,
 *  so a crash mid-save could leave the file empty or torn - unrecoverable for field notes (#153).
 *  The rename is atomic on the same filesystem, so a complete file exists at every instant; the
 *  fsync makes sure the new content is on disk before the rename points at it. */
private fun writeAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray())
        out.fd.sync()
    }
    if (!tmp.renameTo(f)) throw java.io.IOException("rename failed: $tmp -> $f")
}

/**
 * Dev seeding. The debug build ships non-debuggable (it runs at release speed), so `run-as` can't
 * reach its files, and notes.json lives in internal storage that `adb push` can't write either. So
 * `just seed` pushes a seed file to the external files dir (which adb push *can* reach), and
 * the dev build imports it into notes.json on the next launch and deletes it. Gated to the `.debug`
 * package so the real release app never imports a pushed file. Call before [loadNotes].
 */
fun importSeedNotes(ctx: Context) {
    if (!ctx.packageName.endsWith(".debug")) return
    val seed = File(ctx.getExternalFilesDir(null), "seed-notes.json")
    if (!seed.exists()) return
    runCatching {
        notesFile(ctx).writeText(seed.readText())
        seed.delete()
    }
}

fun loadNotes(ctx: Context): List<Note> {
    val f = notesFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching { parseNotes(f.readText()) }.getOrDefault(emptyList())
}

fun saveNotes(ctx: Context, notes: List<Note>) = writeAtomic(notesFile(ctx), notesJson(notes))

// ---- archive file (#153): where "deleted" observations go, restorable from the archive screen.
// Separate from notes.json so the hot paths (launch parse, every-save rewrite, the Downloads TSV
// mirror) stay proportional to the LIVE notes however many years of archive pile up. ----

private fun archiveFile(ctx: Context) = File(ctx.filesDir, "archive.jsonl")

/** Archiving appends - it never parses the existing archive (the point of JSONL). A torn append
 *  can only corrupt the final line, which [notesFromJsonl] skips, so no atomic dance is needed. */
fun appendToArchive(ctx: Context, notes: List<Note>) {
    if (notes.isEmpty()) return
    archiveFile(ctx).appendText(notesToJsonl(notes))
}

fun loadArchive(ctx: Context): List<Note> {
    val f = archiveFile(ctx)
    if (!f.exists()) return emptyList()
    return runCatching { notesFromJsonl(f.readText()) }.getOrDefault(emptyList())
}

/** Full rewrite - only shrinking the archive (a restore, or an undone archiving) needs this.
 *  An empty archive deletes the file, keeping [archiveExists] a plain existence check. */
fun saveArchive(ctx: Context, notes: List<Note>) {
    val f = archiveFile(ctx)
    if (notes.isEmpty()) f.delete() else writeAtomic(f, notesToJsonl(notes))
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
    return runCatching { parseUses(f.readText(), now) }.getOrDefault(emptyMap())
}

fun saveUses(ctx: Context, uses: Map<String, UseEntry>) = usesFile(ctx).writeText(usesJson(uses))

// ---- per-activity use counts, so your most-used activities rank to the top ----

private fun loadCounts(ctx: Context, file: String): Map<String, Int> {
    val f = File(ctx.filesDir, file)
    if (!f.exists()) return emptyMap()
    return runCatching { parseCounts(f.readText()) }.getOrDefault(emptyMap())
}

private fun saveCounts(ctx: Context, file: String, counts: Map<String, Int>) =
    File(ctx.filesDir, file).writeText(countsJson(counts))

fun loadActUses(ctx: Context) = loadCounts(ctx, "act_uses.json")
fun saveActUses(ctx: Context, uses: Map<String, Int>) = saveCounts(ctx, "act_uses.json", uses)

// ---- co-observer name register (autocomplete) + "følget mitt" (the current party) ----

/** Pick counts per co-observer name, so names you enter often surface first in the picker. */
fun loadCoObsUses(ctx: Context) = loadCounts(ctx, "coobs_uses.json")
fun saveCoObsUses(ctx: Context, uses: Map<String, Int>) = saveCounts(ctx, "coobs_uses.json", uses)

private fun coObsUserFile(ctx: Context) = File(ctx.filesDir, "coobs_users.json")

/** Users picked from the co-observer search (see [AoUser]), by brukeralias. */
fun loadCoObsUsers(ctx: Context): Map<String, AoUser> {
    val f = coObsUserFile(ctx)
    if (!f.exists()) return emptyMap()
    return runCatching { parseCoObsUsers(f.readText()) }.getOrDefault(emptyMap())
}

fun saveCoObsUsers(ctx: Context, users: Collection<AoUser>) = coObsUserFile(ctx).writeText(coObsUsersJson(users))

private fun partyFile(ctx: Context) = File(ctx.filesDir, "party.json")

/** The current field party ("følget mitt"), persisted so it survives restarts - a trip lasts across
 *  app launches, and it can be set from the list header before there are any notes (#176). null when
 *  never written, which seeds it from the newest note instead (the pre-#176 derived behaviour). */
fun loadParty(ctx: Context): List<String>? {
    val f = partyFile(ctx)
    if (!f.exists()) return null
    return runCatching { parseParty(f.readText()) }.getOrNull()
}

fun saveParty(ctx: Context, party: List<String>) = partyFile(ctx).writeText(partyJson(party))

// ---- species-name language preference (#155): which language to show as primary + secondary ----

private fun langPrefsFile(ctx: Context) = File(ctx.filesDir, "lang_prefs.json")

/** Load the saved languages, or the flavor defaults ([Country.defaultPrimary]/[defaultSecondary])
 *  on a fresh install - so out of the box the app matches the old hard-wired per-flavor behaviour. */
fun loadLangPrefs(ctx: Context): LangPrefs {
    val f = langPrefsFile(ctx)
    val default = LangPrefs(Country.defaultPrimary, Country.defaultSecondary)
    if (!f.exists()) return default
    return runCatching { parseLangPrefs(f.readText(), default) }.getOrDefault(default)
}

fun saveLangPrefs(ctx: Context, prefs: LangPrefs) = langPrefsFile(ctx).writeText(langPrefsJson(prefs))

// ---- export (paste format; columns/headers per Country) ----

fun exportTsv(notes: List<Note>): String {
    // Emit the *bare* Lokalitetsnavn with no coordinates. Paste-import behaviour
    // (tested against the live v2.20 site, see docs/artsobs-import.md):
    //  - a comma-qualified name ("Lok, Hovedlok, Kommune, Fylke") HARD-FAILS validation.
    //  - name / name+coords each validate but MINT a private duplicate - paste never
    //    links to a public locality. Only manual selection on the site links to public.
    // So we emit the bare name and let the user scope the import form to the kommune to
    // disambiguate, fixing the rest by hand. Nord/Øst/Nøyaktighet are left blank.
    // As many trailing "Medobservatør" columns as the busiest row needs; 0 when nobody logged a
    // co-observer, so the format is byte-for-byte unchanged when the feature is unused (#128).
    val maxCoObs = notes.maxOfOrNull { it.coObservers.size } ?: 0
    val rows = notes.sortedBy { it.time }.map { n ->
        val d = exportDate(n.time)
        // Til defaults to Fra; an explicit endTime makes it a range (may cross midnight).
        val end = n.endTime ?: n.time
        val dEnd = exportDate(end)
        // A no-time obs keeps its date but leaves both klokkeslett columns blank (the site
        // accepts a date-only observation, like a blank Antall means "unknown").
        val t = if (n.timeUnknown) "" else exportTime(n.time)
        val tEnd = if (n.timeUnknown) "" else exportTime(end)
        val loc = n.locName.ifBlank { n.locFull }
        // A brand-new spot is exported WITH coordinates (+ its radius as Nøyaktighet), which
        // mints a new custom locality on import; registry localities stay name-only so the import
        // links them to the public site (verified on both Artsobservasjoner and Artportalen).
        // Coordinates use the flavor's locale (comma decimal) - the paste-import parses numbers with
        // the account's number format (Norwegian/Swedish), so a period reads as "not a decimal
        // number". The TSV is tab-delimited, so a comma in the value is safe.
        val nord = if (n.newLoc) String.format(Country.displayLocale, "%.6f", n.lat) else ""
        val ost = if (n.newLoc) String.format(Country.displayLocale, "%.6f", n.lon) else ""
        val noy = if (n.newLoc) "${n.locRadius} m" else ""
        // Pad each row's co-observers out to maxCoObs so every row has the same column count. A user
        // picked from the search is stored as their brukeralias, which is what the import links on.
        val coObs = List(maxCoObs) { n.coObservers.getOrElse(it) { "" } }
        // Only the Norwegian template has a hide-until column (Artportalen's "Diffusion" blurs
        // coordinates instead - a different mechanism), so the cell exists only where the header does.
        val hide = if (Country.hideUntilCol != null) listOf(n.hideUntil?.let(::exportDate) ?: "") else emptyList()
        (listOf(
            n.species, if (n.count == UNKNOWN_COUNT) "" else n.count.toString(), n.age, n.sex, n.activity, loc,
            nord, ost, noy, d, t, dEnd, tEnd, n.publicComment, n.privateComment,
            if (n.uncertain) Country.uncertainYes else "",
        ) + hide + coObs).joinToString("\t") { cell ->
            // A tab or newline in a free-text comment would split the row and desync
            // every following column on paste-import; flatten them to spaces.
            cell.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        }
    }
    val header = (Country.exportCols + List(maxCoObs) { Country.coObserverCol }).joinToString("\t")
    return (listOf(header) + rows).joinToString("\n")
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
