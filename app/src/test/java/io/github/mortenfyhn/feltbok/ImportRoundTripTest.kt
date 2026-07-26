package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The offline half of the export-format contract (#142): a fake importer that parses the TSV back
 * the way Artsobservasjoner's paste-import is observed to (docs/artsobs-import.md), then asserts
 * the parsed rows reconstruct the source notes. ModelTest pins individual cells; this locks the
 * whole-format contract — column alignment, header-name matching, the hard-fail rules — so an
 * export change can't silently break the paste, and it runs in CI with the site down. It's only as
 * correct as our understanding of the real site; the live seed→export→paste step in
 * docs/pre-release-checklist.md stays the ground truth for that.
 */
class ImportRoundTripTest {

    @Before
    fun fixTimeZone() {
        // exportDate/exportTime use the default zone; pin it so dates are deterministic.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    // The fake importer encodes the Norwegian (old-site v2.20) headers and number format; the
    // Sweden flavor has its own. The alignment/empty-batch tests below run for both.
    private val isNorwayExport = "Artsnavn" in Country.exportCols

    /** What the paste-import reconstructs from one row — the fields the app round-trips. */
    private data class ImportedRow(
        val species: String, val count: Int?, val age: String, val sex: String, val activity: String,
        val locality: String, val lat: Double?, val lon: Double?, val accuracyM: Int?,
        val fromDate: String, val fromTime: String, val toDate: String, val toTime: String,
        val publicComment: String, val privateComment: String, val uncertain: Boolean,
        val coObservers: List<String>,
    )

    /** Parse a pasted TSV the way the live import does, hard-failing on everything observed to
     *  hard-fail on the real site (docs/artsobs-import.md). Columns are matched by header NAME,
     *  never position — so the lookups themselves assert each header, including the template's
     *  misspelt "artsbestemming" (a correctly-spelt header would orphan the flag column). */
    private fun fakeImport(tsv: String): List<ImportedRow> {
        val lines = tsv.split("\n")
        val header = lines[0].split("\t")
        fun col(name: String) = header.indexOf(name).also { assertTrue("header '$name' missing", it >= 0) }
        val species = col("Artsnavn"); val antall = col("Antall")
        val alder = col("Alder"); val kjonn = col("Kjønn"); val aktivitet = col("Aktivitet")
        val lokalitet = col("Lokalitetsnavn")
        val nord = col("Nord"); val ost = col("Øst"); val noyaktighet = col("Nøyaktighet")
        val fraDato = col("Fra dato"); val fraKl = col("Fra klokkeslett")
        val tilDato = col("Til dato"); val tilKl = col("Til klokkeslett")
        val kommentar = col("Kommentar (synlig for alle)")
        val privat = col("Privat kommentar (kun synlig for deg selv)")
        val usikker = col("Usikker artsbestemming")
        val medobs = header.withIndex().filter { it.value == "Medobservatør" }.map { it.index }

        // Numbers are parsed with the account's Norwegian format: a period is rejected as
        // "not a decimal number", so only a comma decimal reads as a coordinate.
        fun coord(cell: String): Double? {
            if (cell.isEmpty()) return null
            assertTrue("'$cell' is not a Norwegian decimal number", Regex("""-?\d+,\d+""").matches(cell))
            return cell.replace(',', '.').toDouble()
        }

        // "Lokaliteten må ha en nøyaktighet som er et positivt heltall" - 0 m hard-fails.
        fun accuracy(cell: String): Int? {
            if (cell.isEmpty()) return null
            val m = Regex("""([1-9]\d*) m""").matchEntire(cell)
            assertTrue("'$cell' is not a positive whole number of metres", m != null)
            return m!!.groupValues[1].toInt()
        }
        fun date(cell: String) = cell.also {
            assertTrue("'$it' is not dd.MM.yyyy", Regex("""\d{2}\.\d{2}\.\d{4}""").matches(it))
        }
        fun time(cell: String) = cell.also {
            assertTrue("'$it' is not HH:mm or blank", it.isEmpty() || Regex("""\d{2}:\d{2}""").matches(it))
        }

        return lines.drop(1).map { line ->
            val c = line.split("\t")
            // A cell count differing from the header's desyncs every following column on paste.
            assertEquals("row and header column counts", header.size, c.size)
            assertTrue("Antall must be blank or positive", c[antall].isEmpty() || c[antall].toInt() > 0)
            assertTrue("Lokalitetsnavn must not be blank", c[lokalitet].isNotBlank())
            // The checkbox cell accepts «X»/«ja»/«1» per the template; anything else fails.
            assertTrue("'${c[usikker]}' is not a checkbox value", c[usikker] in listOf("", "Ja", "ja", "X", "1"))
            ImportedRow(
                species = c[species], count = c[antall].ifEmpty { null }?.toInt(),
                age = c[alder], sex = c[kjonn], activity = c[aktivitet],
                locality = c[lokalitet], lat = coord(c[nord]), lon = coord(c[ost]),
                accuracyM = accuracy(c[noyaktighet]),
                fromDate = date(c[fraDato]), fromTime = time(c[fraKl]),
                toDate = date(c[tilDato]), toTime = time(c[tilKl]),
                publicComment = c[kommentar], privateComment = c[privat],
                uncertain = c[usikker].isNotEmpty(),
                coObservers = medobs.map { c[it] }.filter { it.isNotEmpty() },
            )
        }
    }

    private fun at(day: Int, h: Int, min: Int) =
        LocalDateTime.of(2026, 6, day, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun note(ms: Long) = Note(
        id = ms, species = "Gråmåke", latin = "Larus argentatus", count = 3,
        age = "Adult", activity = "Rastende", sex = "Hann",
        publicComment = "på sjøen", privateComment = "notat",
        locName = "Titran", locFull = "Titran, Frøya, Tø", lat = 63.67, lon = 8.31,
    )

    @Test
    fun exportRoundTripsThroughTheFakeImporter() {
        assumeTrue(isNorwayExport)
        // One note per exportTsv path, mirroring the live seed batch: a plain registry locality, a
        // brand-new spot (coords + radius), unknown count, uncertain, a multi-day range, a no-time
        // day, and co-observers (which pad every other row with blank trailing columns).
        val notes = listOf(
            note(at(1, 9, 30)),
            note(at(1, 10, 0)).copy(species = "Teist", latin = "Cepphus grylle", locName = "Fjelltjønna",
                newLoc = true, locRadius = 50, lat = 63.5, lon = 8.25),
            note(at(1, 11, 0)).copy(count = UNKNOWN_COUNT, uncertain = true),
            note(at(1, 12, 0)).copy(endTime = at(2, 1, 30)),
            note(at(3, 0, 0)).copy(timeUnknown = true),
            note(at(3, 8, 0)).copy(coObservers = listOf("Kari Nordmann", "Ola Hansen")),
        )
        val rows = fakeImport(exportTsv(notes))
        val base = ImportedRow(
            species = "Gråmåke", count = 3, age = "Adult", sex = "Hann", activity = "Rastende",
            locality = "Titran", lat = null, lon = null, accuracyM = null,
            fromDate = "01.06.2026", fromTime = "09:30", toDate = "01.06.2026", toTime = "09:30",
            publicComment = "på sjøen", privateComment = "notat", uncertain = false,
            coObservers = emptyList(),
        )
        assertEquals(
            listOf(
                base,
                base.copy(species = "Teist", locality = "Fjelltjønna",
                    lat = 63.5, lon = 8.25, accuracyM = 50, fromTime = "10:00", toTime = "10:00"),
                base.copy(count = null, uncertain = true, fromTime = "11:00", toTime = "11:00"),
                base.copy(fromTime = "12:00", toDate = "02.06.2026", toTime = "01:30"),
                base.copy(fromDate = "03.06.2026", fromTime = "", toDate = "03.06.2026", toTime = ""),
                base.copy(fromDate = "03.06.2026", fromTime = "08:00", toDate = "03.06.2026", toTime = "08:00",
                    coObservers = listOf("Kari Nordmann", "Ola Hansen")),
            ),
            rows,
        )
    }

    @Test
    fun emptyNotesExportHeaderRowOnly() {
        // Nothing logged -> exactly the header line, no data rows and no trailing newline.
        assertEquals(Country.exportCols.joinToString("\t"), exportTsv(emptyList()))
    }

    @Test
    fun everyRowKeepsTheHeadersColumnCountAcrossAHeterogeneousBatch() {
        // The alignment guarantee across a whole mixed batch (fakeImport re-checks it per row, but
        // only for the Norway flavor - this one runs for both): co-observer padding, flattened
        // tabs/newlines, and new-spot coordinates must never change a row's cell count.
        val notes = listOf(
            note(at(1, 9, 0)),
            note(at(1, 10, 0)).copy(publicComment = "med\ttab\nog linjeskift", newLoc = true, locRadius = 25),
            note(at(1, 11, 0)).copy(coObservers = listOf("Kari Nordmann", "Ola Hansen", "Per Olsen")),
        )
        val lines = exportTsv(notes).split("\n")
        assertEquals("header + one line per note", notes.size + 1, lines.size)
        val width = lines[0].split("\t").size
        assertEquals(Country.exportCols.size + 3, width) // padded out to the busiest row's co-observers
        lines.forEachIndexed { i, line -> assertEquals("line $i", width, line.split("\t").size) }
    }
}
