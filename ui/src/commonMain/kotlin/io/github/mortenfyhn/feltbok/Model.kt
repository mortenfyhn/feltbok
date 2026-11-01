package io.github.mortenfyhn.feltbok

// Per-country option vocabularies, export format, geography and service URLs now live in Country.kt.

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()} m" else "${formatDecimal(m / 1000, 1)} km"

// ---- date/time formatting (the rest is in Dates.kt) ----

// Compact forms of age/sex for the list-row preview, so two near-identical observations are
// tellable apart at a glance (which is the chicks, which the parents). The row builds the preview
// itself (it bolds the thin sex symbol), so these stay as small reusable formatters.
fun shortAge(age: String): String = when (age) {
    "Adult" -> "Ad"
    "Pulli" -> "Pu"
    "Egg" -> "Eg"
    else -> age // the K-ages (1K, 2K+, …) are already short
}

// The trailing ︎ (VS15) forces text presentation, so ♂/♀ render from the regular text font
// instead of the emoji font — without it they come out as tall colour emoji that inflate the row.
fun sexSymbol(sex: String): String = when (sex) {
    "Hann" -> "♂︎"
    "Hunn" -> "♀︎"
    "Hunnfarget" -> "(♀︎)"
    "I par" -> "♂︎♀︎"
    else -> sex
}

// Export date/time use the country's numeric format.
fun exportDate(ms: Long): String = formatDateTime(ms, Country.exportDateFmt)
fun exportTime(ms: Long): String = formatDateTime(ms, Country.exportTimeFmt)

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
        // Coordinates use the display locale (comma decimal) - the paste-import parses numbers with
        // the account's number format (Norwegian), so a period reads as "not a decimal
        // number". The TSV is tab-delimited, so a comma in the value is safe.
        val nord = if (n.newLoc) formatDecimal(n.lat, 6, ',') else ""
        val ost = if (n.newLoc) formatDecimal(n.lon, 6, ',') else ""
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
