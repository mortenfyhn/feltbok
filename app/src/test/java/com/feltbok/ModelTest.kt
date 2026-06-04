package com.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/** Unit tests for the pure logic that shapes the Artsobservasjoner export and the
 *  distance display — the parts a regression would silently break. */
class ModelTest {

    @Before
    fun fixTimeZone() {
        // exportDate/exportTime use the default zone; pin it so dates are deterministic.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun noteAt(ms: Long) = Note(
        id = ms, species = "Gråmåke", latin = "Larus argentatus", count = 3,
        age = "Adult", activity = "Næringssøk", sex = "Hann",
        publicComment = "på sjøen", privateComment = "test",
        locName = "Titran", locFull = "Titran, Frøya, Tø", lat = 63.67, lon = 8.31,
    )

    private val noonMs = LocalDateTime.of(2026, 6, 1, 9, 30, 0)
        .toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun exportRowHasColumnsInOrderWithBlankCoords() {
        val lines = exportTsv(listOf(noteAt(noonMs))).split("\n")
        assertEquals("header + one row", 2, lines.size)
        val c = lines[1].split("\t")
        assertEquals(16, c.size)
        assertEquals("Gråmåke", c[0])
        assertEquals("3", c[1])
        assertEquals("Adult", c[2])
        assertEquals("Hann", c[3])
        assertEquals("Næringssøk", c[4])
        assertEquals("Titran", c[5])   // bare name: a qualified name hard-fails paste validation
        // Coordinates are deliberately omitted (name+coords mints a private duplicate).
        assertEquals("", c[6])
        assertEquals("", c[7])
        assertEquals("", c[8])
        // From/til date and time are the same single instant.
        assertEquals("01.06.2026", c[9])
        assertEquals("09:30", c[10])
        assertEquals(c[9], c[11])
        assertEquals(c[10], c[12])
        assertEquals("på sjøen", c[13])
        assertEquals("test", c[14])
        assertEquals("", c[15])   // Usikker artsbestemming: blank unless flagged
    }

    @Test
    fun uncertainExportsUnderTheRegisteredMisspeltHeader() {
        // Paste-import matches columns by header name; the header must reproduce the
        // template's "artsbestemming" misspelling or a flagged row fails validation.
        val lines = exportTsv(listOf(noteAt(noonMs).copy(uncertain = true))).split("\n")
        assertEquals("Usikker artsbestemming", lines[0].split("\t")[15])
        assertEquals("Ja", lines[1].split("\t")[15])
    }

    @Test
    fun exportUsesEndTimeForTilWhenSet() {
        // A range spanning into the next day: Fra and Til must differ on both date and time.
        val end = noonMs + 16 * 3_600_000  // +16h -> next day 01:30
        val c = exportTsv(listOf(noteAt(noonMs).copy(endTime = end))).split("\n")[1].split("\t")
        assertEquals("01.06.2026", c[9]); assertEquals("09:30", c[10])
        assertEquals("02.06.2026", c[11]); assertEquals("01:30", c[12])
    }

    @Test
    fun exportSortsByTimeAscending() {
        val early = noteAt(noonMs)
        val late = noteAt(noonMs + 3_600_000)
        val rows = exportTsv(listOf(late, early)).split("\n").drop(1)
        assertEquals("09:30", rows[0].split("\t")[10])
        assertEquals("10:30", rows[1].split("\t")[10])
    }

    @Test
    fun haversineIsZeroForSamePointAndAboutOneEleventhKmPerDegree() {
        assertEquals(0.0, haversine(63.7, 8.8, 63.7, 8.8), 1e-6)
        val d = haversine(63.0, 8.0, 64.0, 8.0)
        assertTrue("≈111 km, got $d", d in 110_000.0..112_000.0)
    }

    @Test
    fun formatDistanceUsesMetersThenKilometers() {
        assertEquals("500 m", formatDistance(500.0))
        assertEquals("1.5 km", formatDistance(1500.0))
    }

    @Test
    fun fuzzyRanksByMatchQuality() {
        assertEquals(0, fuzzyScore("rødv", "Rødvingetrost"))    // prefix
        assertEquals(1, fuzzyScore("rvt", "Rødvingetrost"))     // first letter + subsequence
        assertEquals(2, fuzzyScore("trost", "Rødvingetrost"))   // mid-word substring
        assertEquals(null, fuzzyScore("xyz", "Rødvingetrost"))  // no match
    }

    @Test
    fun fuzzyWeightsFirstLetterMatchAboveMidWord() {
        // "pf": Pilfink starts with p (rank 1), Lappfiskand only has p/f mid-word.
        val pilfink = fuzzyScore("pf", "Pilfink")!!
        val lappfiskand = fuzzyScore("pf", "Lappfiskand")!!
        assertTrue("Pilfink ($pilfink) should outrank Lappfiskand ($lappfiskand)",
            pilfink < lappfiskand)
    }

    @Test
    fun fuzzyFoldsNorwegianLettersSoAsciiMatches() {
        assertEquals(0, fuzzyScore("rodvinge", "Rødvingetrost"))
        assertEquals(1, fuzzyScore("blmeis", "Blåmeis"))   // first letter b + subsequence
    }

    @Test
    fun fuzzyIgnoresSpacesAndCase() {
        assertEquals(1, fuzzyScore("R V T", "Rødvingetrost"))
    }
}
