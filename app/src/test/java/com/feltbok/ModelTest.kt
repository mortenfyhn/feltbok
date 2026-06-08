package com.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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

    // ---- species search ranking (#64: common birds must beat rare prefix matches) ----

    /** A few species in Norway-wide frequency order (common first), as the bundled CSV is. */
    private fun freqOrdered(vararg norsk: String) = norsk.map { Species(it, it.lowercase()) }

    private fun rank(query: String, list: List<Species>) =
        searchSpecies(query, list, list.map { fold(it.norsk) }, useCount = { 0 }).map { it.norsk }

    @Test
    fun searchRanksCommonPrefixMatchAboveRareOnes() {
        // "gul" prefix-matches the common Gulspurv and a swarm of rare vagrants alike; the
        // common one (earlier in frequency order) must come first, not the rarities.
        val list = freqOrdered(
            "Gulspurv", "Gulerle", "Gulsanger",          // common, early in the list
            "Gulkinnand", "Gulbeinsnipe", "Gulstrupespurv", "Gullfasan",  // rare vagrants
        )
        assertEquals("Gulspurv", rank("gul", list).first())
        assertEquals(listOf("Gulspurv", "Gulerle", "Gulsanger"), rank("gul", list).take(3))
    }

    @Test
    fun searchSurfacesCommonSandSpecies() {
        val list = freqOrdered(
            "Sandlo", "Sandsvale", "Sandløper",          // common waders/swallow
            "Sandterne", "Sandsnipe",                    // rarer
            "Sørblesand", "Laksand",                     // 'sand' only mid-word
        )
        assertEquals(listOf("Sandlo", "Sandsvale"), rank("sand", list).take(2))
    }

    @Test
    fun searchTieBreaksOnPersonalUseCount() {
        // Among equal-quality matches, a species the user picks often outranks frequency order.
        val list = freqOrdered("Gulspurv", "Gulsanger")
        val ranked = searchSpecies("gul", list, list.map { fold(it.norsk) },
            useCount = { if (it == "Gulsanger") 5 else 0 })
        assertEquals("Gulsanger", ranked.first().norsk)
    }

    // ---- locality tap hit-testing (#63: a tap inside a polygon must pick that polygon) ----

    private fun square(latLo: Double, latHi: Double, lonLo: Double, lonHi: Double) = listOf(
        doubleArrayOf(latLo, lonLo), doubleArrayOf(latLo, lonHi),
        doubleArrayOf(latHi, lonHi), doubleArrayOf(latHi, lonLo),
    )

    @Test
    fun pointInPolygonDetectsInsideAndOutside() {
        val sq = square(63.0, 64.0, 8.0, 9.0)
        assertTrue(pointInPolygon(63.5, 8.5, sq))      // centre
        assertTrue(pointInPolygon(63.99, 8.01, sq))    // near a corner, still inside
        assertTrue(!pointInPolygon(64.5, 8.5, sq))     // north of the box
        assertTrue(!pointInPolygon(63.5, 9.5, sq))     // east of the box
    }

    @Test
    fun localityContainsPrefersFootprintOverNearerCentre() {
        // The #63 case: a tap inside polygon A but closer to point-locality B's centre. A contains
        // the tap; B (a point, no footprint) does not - so the picker keeps A.
        val a = Locality("a", "Polygon A", "", "", 63.5, 8.5, "A", 0, 0.0,
            polygon = square(63.0, 64.0, 8.0, 9.0))
        val b = Locality("b", "Point B", "", "", 64.05, 9.05, "B", 0, 0.0)  // just outside A, nearer the corner
        val tapLat = 63.98; val tapLon = 8.98          // inside A, near its NE corner, close to B
        assertTrue(localityContains(a, tapLat, tapLon))
        assertTrue(!localityContains(b, tapLat, tapLon))
    }

    @Test
    fun localityContainsUsesRadiusForCircles() {
        val circle = Locality("c", "Circle", "", "", 63.0, 8.0, "C", 0, 200.0)  // 200 m radius
        assertTrue(localityContains(circle, 63.0, 8.0))                 // dead centre
        assertTrue(!localityContains(circle, 63.1, 8.0))               // ~11 km away
        val point = circle.copy(radius = 0.0)
        assertTrue(!localityContains(point, 63.0, 8.0))                // a point has no footprint
    }

    private fun mine(id: String, name: String = "Lok $id", lat: Double = 63.0, radius: Double = 0.0) =
        Locality(id, name, "", "", lat, 8.0, name, 0, radius, public = false, mine = true)

    @Test
    fun diffCountsAddedRemovedAndModifiedAsOneSum() {
        val old = listOf(mine("1"), mine("2"), mine("3"))
        val new = listOf(
            mine("1"),                         // unchanged
            mine("2", lat = 64.0),             // moved -> modified
            mine("4"),                         // added (3 is removed)
        )
        val d = diffMySites(old, new)
        assertEquals(3, d.total)
        assertEquals("1 added + 1 removed + 1 modified", 3, d.changed)
        assertTrue(!d.firstSync)
    }

    @Test
    fun diffFromEmptyIsFirstSync() {
        val d = diffMySites(emptyList(), listOf(mine("1"), mine("2")))
        assertTrue(d.firstSync)
        assertEquals(2, d.total)
    }

    @Test
    fun diffWithNoChangeReportsZeroChanged() {
        val set = listOf(mine("1"), mine("2"))
        val d = diffMySites(set, set)
        assertEquals(0, d.changed)
        assertTrue(!d.firstSync)
    }

    @Test
    fun contextualFrequencyFollowsMonthAndPlace() {
        val summer = Species("Sommerfugl", "Aestas aestas", count = 1000)
        val winter = Species("Vinterfugl", "Hiems hiems", count = 1000)
        val southern = Species("Sørfugl", "Meridies avis", count = 100) // less common nationally
        val species = listOf(summer, winter, southern)
        val monthly = mapOf(
            "Aestas aestas" to intArrayOf(0, 0, 0, 0, 1000, 0, 0, 0, 0, 0, 0, 0), // peaks May
            "Hiems hiems" to intArrayOf(1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1000), // peaks Dec/Jan
        )
        val cell = ContextualFrequency.cellKey(58.5, 8.5)
        val regions = mapOf(cell to mapOf("Meridies avis" to 1000)) // southern bird is local here
        val f = ContextualFrequency(species, monthly, regions)
        // Season: the right bird leads for the month, regardless of location.
        assertTrue(f.weight(summer, 5, null, null) > f.weight(winter, 5, null, null))
        assertTrue(f.weight(winter, 12, null, null) > f.weight(summer, 12, null, null))
        // Place: the southern bird ranks higher when we're in its cell than with no fix.
        assertTrue(f.weight(southern, 6, 58.5, 8.5) > f.weight(southern, 6, null, null))
    }

    @Test
    fun groupNotesByDaySplitsLabelsAndOrdersByTimeNotEntry() {
        // Regression for #44: notes spanning days land in separate, correctly-labelled sections,
        // ordered newest-day-first by observation time — a note dated in the past must NOT jump to
        // the top just because it was entered last (input here is deliberately out of order).
        val today = LocalDate.of(2026, 6, 8)
        fun at(y: Int, mo: Int, d: Int, h: Int) =
            noteAt(LocalDateTime.of(y, mo, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
        val groups = groupNotesByDay(
            listOf(at(2025, 6, 8, 9), at(2026, 6, 1, 9), at(2026, 6, 8, 8), at(2026, 6, 7, 7), at(2026, 6, 8, 10)),
            today = today, zone = ZoneId.of("UTC"),
        )
        // Older-than-yesterday days read as abbreviated dates; the year shows only off the current one.
        assertEquals(listOf("I dag", "I går", "Man 1. jun", "Søn 8. jun 2025"), groups.map { it.label })
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.notes.size })
        // Within a day, the latest observation comes first.
        assertEquals(groups[0].notes.map { it.time }.sortedDescending(), groups[0].notes.map { it.time })
    }
}
