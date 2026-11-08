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
    // Lokalitetsnavn carries the site id when the note has one: the import links a bare id to
    // exactly that site, public or the user's own, while a name can match several sites
    // (docs/artsobs-import.md). Older notes without an id fall back to the bare registered name.
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
        val loc = if (n.newLoc || n.locId.isBlank()) n.locName.ifBlank { n.locFull } else n.locId
        // A brand-new spot is exported WITH coordinates (+ its radius as Nøyaktighet), which
        // mints a new custom locality on import; registry localities carry no coordinates, which
        // would also mint a duplicate.
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

/** Kommune of the registry locality nearest [lat]/[lon], for stamping a brand-new spot with the
 *  kommune around it. Only localities that carry a kommune count (a new spot is itself a
 *  kommune-less private locality, so it can't be its own answer). Blank until localities load. */
fun nearestKommune(lat: Double, lon: Double, localities: List<Locality>): String =
    localities.filter { it.kommune.isNotBlank() }
        .minByOrNull { haversine(it.lat, it.lon, lat, lon) }?.kommune ?: ""
