package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    // These golden tests pin the Norwegian Artsobservasjoner export format (dd.MM.yyyy dates,
    // name-only/blank coordinates, the deliberately-misspelt header). Under another country flavor
    // Country yields a different format, so they only apply to the Norway build.
    private val isNorwayExport = "Artsnavn" in Country.exportCols

    @Test
    fun exportRowHasColumnsInOrderWithBlankCoords() {
        assumeTrue(isNorwayExport)
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
        assumeTrue(isNorwayExport)
        val lines = exportTsv(listOf(noteAt(noonMs).copy(uncertain = true))).split("\n")
        assertEquals("Usikker artsbestemming", lines[0].split("\t")[15])
        assertEquals("Ja", lines[1].split("\t")[15])
    }

    @Test
    fun unknownCountExportsAsBlankAntall() {
        // -1 is the "unknown number of individuals" sentinel; Artsobservasjoner reads a
        // blank Antall as unknown, so it must not export as "-1".
        val c = exportTsv(listOf(noteAt(noonMs).copy(count = UNKNOWN_COUNT))).split("\n")[1].split("\t")
        assertEquals("", c[1])
    }

    @Test
    fun exportUsesEndTimeForTilWhenSet() {
        // A range spanning into the next day: Fra and Til must differ on both date and time.
        assumeTrue(isNorwayExport)
        val end = noonMs + 16 * 3_600_000  // +16h -> next day 01:30
        val c = exportTsv(listOf(noteAt(noonMs).copy(endTime = end))).split("\n")[1].split("\t")
        assertEquals("01.06.2026", c[9]); assertEquals("09:30", c[10])
        assertEquals("02.06.2026", c[11]); assertEquals("01:30", c[12])
    }

    @Test
    fun timeUnknownExportsBlankKlokkeslettButKeepsDates() {
        // "Uten klokkeslett": the date is still emitted (from and til), only the two time
        // columns go blank - the site accepts a date-only observation.
        assumeTrue(isNorwayExport)
        val c = exportTsv(listOf(noteAt(noonMs).copy(endTime = noonMs, timeUnknown = true))).split("\n")[1].split("\t")
        assertEquals("01.06.2026", c[9]); assertEquals("", c[10])
        assertEquals("01.06.2026", c[11]); assertEquals("", c[12])
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
    fun exportFlattensTabsAndNewlinesInCommentsToKeepColumnsAligned() {
        // A stray tab or newline in a free-text comment would split the row and silently
        // desync every following column on paste-import - flatten them to spaces.
        val n = noteAt(noonMs).copy(
            publicComment = "ser\tut\nsom\rfjellmåke",
            privateComment = "linje1\nlinje2",
        )
        val lines = exportTsv(listOf(n)).split("\n")
        assertEquals("header + one row (no comment broke the row count)", 2, lines.size)
        val c = lines[1].split("\t")
        assertEquals(16, c.size)
        assertEquals("ser ut som fjellmåke", c[13])
        assertEquals("linje1 linje2", c[14])
    }

    @Test
    fun newLocExportsCoordinatesAndRadiusAsNoyaktighet() {
        // A brand-new spot is exported WITH coordinates (+ radius as Nøyaktighet) so the
        // import mints it; registry localities stay name-only (covered by the test above).
        // Coordinates use a comma decimal (the import parses numbers with the account's locale;
        // both nb-NO and sv-SE use a comma) - a period is rejected as "not a decimal number".
        val n = noteAt(noonMs).copy(newLoc = true, locRadius = 50)
        val c = exportTsv(listOf(n)).split("\n")[1].split("\t")
        assertEquals("63,670000", c[6])
        assertEquals("8,310000", c[7])
        assertEquals("50 m", c[8])
    }

    @Test
    fun coObserversAppendMedobservatorColumnsPaddedToTheBusiestRow() {
        // Paste-import matches by header name, so each co-observer becomes an identically-headed
        // trailing column; every row is padded to the max count so the grid stays rectangular (#128).
        assumeTrue(isNorwayExport)
        val solo = noteAt(noonMs)
        val duo = noteAt(noonMs + 3_600_000).copy(coObservers = listOf("Kari Nordmann", "Ola Hansen"))
        val lines = exportTsv(listOf(solo, duo)).split("\n")
        val header = lines[0].split("\t")
        assertEquals(16 + 2, header.size)
        assertEquals("Medobservatør", header[16]); assertEquals("Medobservatør", header[17])
        val soloRow = lines[1].split("\t")   // sorted by time: solo first
        assertEquals(18, soloRow.size)
        assertEquals("", soloRow[16]); assertEquals("", soloRow[17])   // padded blanks
        val duoRow = lines[2].split("\t")
        assertEquals("Kari Nordmann", duoRow[16]); assertEquals("Ola Hansen", duoRow[17])
    }

    @Test
    fun noCoObserversLeavesExportFormatUnchanged() {
        // The feature must be zero-cost when unused: no co-observers -> no extra columns at all.
        val lines = exportTsv(listOf(noteAt(noonMs))).split("\n")
        assertEquals(Country.exportCols.size, lines[0].split("\t").size)
    }

    @Test
    fun swedenExportUsesSwedishHeadersAndIsoDateAndNameOnly() {
        assumeTrue(!isNorwayExport) // the Sweden (Artportalen) flavor's export format
        val lines = exportTsv(listOf(noteAt(noonMs))).split("\n")
        val header = lines[0].split("\t")
        assertEquals("Artnamn", header[0])
        assertEquals("Osäker artbestämning", header.last())
        val c = lines[1].split("\t")
        assertEquals("2026-06-01", c[9])        // ISO date, not dd.MM.yyyy
        assertEquals("", c[6]); assertEquals("", c[7])  // registry locality: name-only, links to public
    }

    // ---- multilingual species search + name selection (#155) ----

    @Test
    fun speciesNameReturnsTheChosenLanguage() {
        val s = Species(latin = "Parus major", norsk = "kjøttmeis", svensk = "talgoxe")
        assertEquals("kjøttmeis", s.name(Lang.NORSK))
        assertEquals("talgoxe", s.name(Lang.SVENSK))
        assertEquals("Parus major", s.name(Lang.LATIN))
    }

    @Test
    fun everyNameIsSearchableAndDedupes() {
        // Each species is findable by its Norwegian, Swedish, or Latin name, whichever the birder
        // types - and a match collapses to one species (three alias entries, same species).
        val species = listOf(
            Species(latin = "Parus major", norsk = "kjøttmeis", svensk = "talgoxe"),
            Species(latin = "Cyanistes caeruleus", norsk = "blåmeis", svensk = "blåmes"),
        )
        val prepared = prepare(species)
        assertEquals(6, prepared.size) // 2 species × (norsk + svensk + latin)
        val scorer = TieredScorer({ 0.0 })
        listOf("kjøttmeis", "talgoxe", "parus").forEach { q ->
            assertEquals("query '$q' finds Parus major", "Parus major", scorer.search(q, prepared).first().species.latin)
        }
        // The alias entries resolve to one species, so callers dedupe to one row.
        assertEquals(1, scorer.search("kjøttmeis", prepared).map { it.species }.distinct().count { it.latin == "Parus major" })
    }

    @Test
    fun blankNamesDontCreateAliases() {
        // A Latin-only species (a source lacked both vernaculars) yields a single prepared entry.
        val prepared = prepare(listOf(Species(latin = "Parus major", norsk = "", svensk = "")))
        assertEquals(1, prepared.size)
    }

    @Test
    fun searchOnlyMatchesTheSelectedLanguages() {
        // Default: search matches the primary language only, so a bird isn't surfaced via a language
        // the user isn't looking in (#155 - typing "t" mustn't return kjøttmeis via Swedish talgoxe).
        val species = listOf(Species(latin = "Parus major", norsk = "kjøttmeis", svensk = "talgoxe"))
        val scorer = TieredScorer({ 0.0 })
        val primaryOnly = prepare(species, setOf(Lang.NORSK))
        assertEquals(1, primaryOnly.size)
        assertTrue(scorer.search("kjøttmeis", primaryOnly).isNotEmpty())
        assertTrue("Swedish name not searched", scorer.search("talgoxe", primaryOnly).isEmpty())
        // Opting the secondary in makes the Swedish name findable again.
        assertTrue(scorer.search("talgoxe", prepare(species, setOf(Lang.NORSK, Lang.SVENSK))).isNotEmpty())
    }

    @Test
    fun searchLangsFollowTheSecondaryToggle() {
        val prefs = LangPrefs(Lang.NORSK, Lang.LATIN)
        assertEquals(setOf(Lang.NORSK), prefs.searchLangs)
        assertEquals(setOf(Lang.NORSK, Lang.LATIN), prefs.copy(searchSecondary = true).searchLangs)
    }

    @Test
    fun exportUsesRegistryNameNotTheDisplayName() {
        // The pasted Artsnavn/Artnamn must always be the destination portal's language
        // (Country.exportLang) - exactly what pickSpecies stores - regardless of which language the
        // user has chosen to *see*. So export is decoupled from the display-language setting (#155).
        val crow = Species(latin = "Corvus corone", norsk = "kråke", svensk = "kråka")
        val exportName = crow.name(Country.exportLang) // what MainViewModel.pickSpecies writes to the note
        assertEquals(if (isNorwayExport) "kråke" else "kråka", exportName)
        // A vernacular registry name, never the Latin (or other language) a user might be displaying.
        assertTrue(exportName != crow.latin && exportName != crow.name(Lang.LATIN))

        val note = noteAt(noonMs).copy(species = exportName, latin = crow.latin)
        val artnamn = exportTsv(listOf(note)).lineSequence().drop(1).first().split("\t")[0]
        assertEquals(exportName, artnamn)
    }

    @Test
    fun withUniqueIdsNudgesCollisionsApartWithoutDroppingOrReorderingNotes() {
        // Regression for #85: two observations minted in the same millisecond shared an id, which
        // is the LazyColumn key - duplicates crashed the list on launch. Healing must keep every
        // note (no dedup) and only bump the id, in order.
        val a = noteAt(100).copy(species = "Grønnfink")
        val b = noteAt(100).copy(species = "Bokfink")   // same id as a
        val c = noteAt(101)                              // already collides with a's bumped 101
        val healed = withUniqueIds(listOf(a, b, c))
        assertEquals("no note dropped", 3, healed.size)
        assertEquals("ids are all distinct", 3, healed.map { it.id }.toSet().size)
        assertEquals("order and content preserved", listOf("Grønnfink", "Bokfink", "Gråmåke"),
            healed.map { it.species })
        assertEquals("first occurrence keeps its id", 100, healed[0].id)
    }

    @Test
    fun noteJsonRoundTripPreservesEveryField() {
        // Guard against a field saved but not restored (or vice versa): silent data loss on the
        // next launch. Every field is a distinct non-default value, so dropping any one fails
        // equality (id and time differ, so a swap is caught too).
        val n = noteAt(1717).copy(time = 1800, endTime = 1900, timeUnknown = true, newLoc = true, locRadius = 50, uncertain = true,
            coObservers = listOf("Kari Nordmann", "Ola Hansen"))
        assertEquals(n, noteFromJson(noteToJson(n)))
        // endTime is the only optional field (omitted from JSON when null); confirm null survives.
        assertEquals(null, noteFromJson(noteToJson(n.copy(endTime = null))).endTime)
        // coObservers is omitted from JSON when empty; confirm an empty list round-trips as empty.
        assertEquals(emptyList<String>(), noteFromJson(noteToJson(n.copy(coObservers = emptyList()))).coObservers)
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

    // ---- species search ranking. TieredScorer is the live ranker (MainViewModel feeds it a
    //      frequency provider that blends commonness with personal use). These guard the
    //      user-visible promises: a common bird beats rare prefix matches (#64), and your own
    //      picks tie-break. They run through the shipping scorer, not a retired helper. ----

    /** A few species in Norway-wide frequency order (common first), as the bundled CSV is;
     *  RowOrderFrequency turns that order into the commonness signal the scorer ranks by. */
    private fun freqOrdered(vararg norsk: String) = norsk.map { Species(latin = it.lowercase(), norsk = it) }

    /** Rank through the shipping scorer. [useCount] folds into the frequency weight exactly as
     *  MainViewModel does - a regular nudges up, capped - so the test mirrors real ranking. */
    private fun rank(query: String, list: List<Species>, useCount: (String) -> Int = { 0 }): List<String> {
        val base = RowOrderFrequency(list)
        val weight: (Species) -> Double = { s ->
            val picks = useCount(s.norsk)
            minOf(1.0, base.weight(s) + if (picks == 0) 0.0 else minOf(0.5, 0.15 * picks))
        }
        // distinct() mirrors MainViewModel.searchResults: a species matched via several of its
        // name-aliases (norsk/svensk/latin) collapses to one row.
        return rankSpecies(query, prepare(list), weight).map { it.species }.distinct().map { it.norsk }
    }

    @Test
    fun searchRanksCommonPrefixMatchAboveRareOnes() {
        // "gul" prefix-matches the common Gulspurv and a swarm of rare vagrants alike; the
        // common ones (earlier in frequency order) must come first, not the rarities.
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
            "Sørblesand", "Laksand",                     // 'sand' only mid-word, so a weaker tier
        )
        assertEquals(listOf("Sandlo", "Sandsvale"), rank("sand", list).take(2))
    }

    @Test
    fun searchTieBreaksOnPersonalUseCount() {
        // Frequent personal picks lift a species past a commoner one of equal match quality. Both
        // Grå-birds prefix-match "grå" and share a length (so completeness can't decide it); without
        // picks the commoner Gråspurv leads, but picking Gråtrost often flips it. Stokkand tops
        // frequency order but doesn't match, so it stays out.
        val list = freqOrdered("Stokkand", "Gråspurv", "Gråtrost")
        assertEquals(listOf("Gråspurv", "Gråtrost"), rank("grå", list))
        assertEquals("Gråtrost", rank("grå", list) { if (it == "Gråtrost") 5 else 0 }.first())
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
        val a = Locality("a", "Polygon A", "", "", 63.5, 8.5, 0, 0.0,
            polygon = square(63.0, 64.0, 8.0, 9.0))
        val b = Locality("b", "Point B", "", "", 64.05, 9.05, 0, 0.0)  // just outside A, nearer the corner
        val tapLat = 63.98; val tapLon = 8.98          // inside A, near its NE corner, close to B
        assertTrue(localityContains(a, tapLat, tapLon))
        assertTrue(!localityContains(b, tapLat, tapLon))
    }

    @Test
    fun localityContainsUsesRadiusForCircles() {
        val circle = Locality("c", "Circle", "", "", 63.0, 8.0, 0, 200.0)  // 200 m radius
        assertTrue(localityContains(circle, 63.0, 8.0))                 // dead centre
        assertTrue(!localityContains(circle, 63.1, 8.0))               // ~11 km away
        val point = circle.copy(radius = 0.0)
        assertTrue(!localityContains(point, 63.0, 8.0))                // a point has no footprint
    }

    @Test
    fun poleOfInaccessibilityCentresASquare() {
        val pole = poleOfInaccessibility(square(63.0, 64.0, 8.0, 9.0))
        assertEquals(63.5, pole[0], 0.02)
        assertEquals(8.5, pole[1], 0.02)
    }

    @Test
    fun poleOfInaccessibilityLandsInsideConcavePolygon() {
        // #135: the banana-shaped "Gaula, Bråleiret-Leinøra, sone 3.3". Its bbox centre falls in the
        // bay of the curve (outside the shape); the pole of inaccessibility must land inside.
        val banana = ("10.184541 63.341189, 10.193424 63.338936, 10.197716 63.34067, " +
            "10.211878 63.343635, 10.22089 63.343019, 10.229387 63.340785, 10.232606 63.340747, " +
            "10.234408 63.340515, 10.235395 63.340535, 10.237584 63.340207, 10.239043 63.34256, " +
            "10.237627 63.342788, 10.233893 63.34383, 10.22737 63.34452, 10.225654 63.344597, " +
            "10.225396 63.344482, 10.221405 63.345021, 10.218487 63.345098, 10.214925 63.345486, " +
            "10.211792 63.346195, 10.211105 63.346061, 10.210762 63.346099").split(",").map {
            val (lo, la) = it.trim().split(" "); doubleArrayOf(la.toDouble(), lo.toDouble())
        }
        assertTrue("bbox centre is outside (the bug)", !pointInPolygon(63.3426, 10.2118, banana))
        val pole = poleOfInaccessibility(banana)
        assertTrue("pole must be inside the polygon", pointInPolygon(pole[0], pole[1], banana))
    }

    @Test
    fun groupNotesByKommuneResolvesByAllThreePathsAndOrders() {
        // Path 1: the note's stamped kommune wins outright - no registry, no fullname needed.
        val nStamped = noteAt(noonMs).copy(locName = "Testdammen", locFull = "", newLoc = true, kommune = "Ørland")
        // Path 2: an unstamped (legacy) note reads kommune from its registered fullname.
        val nFromName = noteAt(noonMs).copy(locFull = "Titran, Frøya, Tø", kommune = "")
        // Path 3: a legacy new spot with neither falls back to the nearest registry kommune (Ørland).
        // The registry also holds the spot itself as a kommune-less locality at distance 0; it must
        // NOT match itself, so blank-kommune candidates are excluded.
        val hovde = Locality("1", "Hovde", "", "Ørland", 63.70, 9.60, 0, 0.0)
        val newSelf = Locality("2", "Testdammen", "", "", 63.71, 9.61, 0, 0.0, public = false, mine = true)
        val nNearest = noteAt(noonMs).copy(locName = "Testdammen", locFull = "", kommune = "", lat = 63.71, lon = 9.61, newLoc = true)

        val groups = groupNotesByKommune(listOf(nNearest, nStamped, nFromName), listOf(hovde, newSelf))
        assertEquals(listOf("Frøya", "Ørland"), groups.map { it.kommune })
        assertEquals("stamped + nearest-resolved both land in Ørland", 2,
            groups.first { it.kommune == "Ørland" }.notes.size)
    }

    @Test
    fun groupNotesByKommuneSingleKommuneYieldsOneGroup() {
        val groups = groupNotesByKommune(listOf(noteAt(noonMs), noteAt(noonMs + 1)), emptyList())
        assertEquals(1, groups.size)
        assertEquals("Frøya", groups[0].kommune)   // noteAt's locFull is "Titran, Frøya, Tø"
    }

    @Test
    fun groupNotesByKommuneFallsBackToBlankForNewSpotWithoutRegistry() {
        // A brand-new spot with no stamp and no fullname, and no localities loaded: kommune unknown.
        val n = noteAt(noonMs).copy(newLoc = true, locName = "Testdammen", locFull = "", kommune = "")
        val groups = groupNotesByKommune(listOf(n), emptyList())
        assertEquals(listOf(""), groups.map { it.kommune })
    }

    private fun mine(id: String, name: String = "Lok $id", lat: Double = 63.0, radius: Double = 0.0) =
        Locality(id, name, "", "", lat, 8.0, 0, radius, public = false, mine = true)

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
    fun parseMySitesKeepsOnlyPrivateRowsWithCoordsAndMapsFields() {
        // The sync ingestion path: /core/Sites/ByUser returns public + private rows; only the
        // isPrivate ones are the user's own customs (public ones are already bundled). A row
        // without usable coordinates is skipped rather than minting a locality at (0,0).
        val json = """
            {"data":[
              {"id":11,"name":"Min vik","presentationName":"Min vik, Frøya, Tø",
               "latitude":63.7,"longitude":8.3,"isPrivate":true,"accuracy":50,
               "municipalityName":"Frøya","isPolygon":false},
              {"id":22,"name":"Allmenn","latitude":63.0,"longitude":8.0,"isPrivate":false},
              {"id":33,"name":"Uten koordinat","isPrivate":true}
            ]}
        """.trimIndent()
        val sites = parseMySites(json)
        assertEquals("only the private, coordinate-bearing row", 1, sites.size)
        val s = sites[0]
        assertEquals("11", s.id)
        assertEquals("Min vik", s.lokalitet)
        assertEquals(63.7, s.lat, 1e-9); assertEquals(8.3, s.lon, 1e-9)
        assertEquals("accuracy -> radius", 50.0, s.radius, 1e-9)
        assertTrue("mine, not public", s.mine && !s.public)
    }

    @Test
    fun parseMySitesConvertsPolygonRingFromLonLatToLatLon() {
        // polygonCoordinates is a JSON string of [lon,lat] vertices (WGS84); our polygon
        // convention is [lat,lon], so the pair order must flip on the way in.
        val json = """
            {"data":[{"id":1,"name":"Polygon","latitude":63.5,"longitude":8.5,"isPrivate":true,
              "isPolygon":true,"polygonCoordinates":"[[8.0,63.0],[9.0,63.0],[9.0,64.0]]"}]}
        """.trimIndent()
        val poly = parseMySites(json).single().polygon
        assertEquals(3, poly.size)
        assertEquals("lat first", 63.0, poly[0][0], 1e-9)
        assertEquals("lon second", 8.0, poly[0][1], 1e-9)
    }

    @Test
    fun contextualFrequencyFollowsMonthAndPlace() {
        val summer = Species(latin = "Aestas aestas", norsk = "Sommerfugl", count = 1000)
        val winter = Species(latin = "Hiems hiems", norsk = "Vinterfugl", count = 1000)
        val southern = Species(latin = "Meridies avis", norsk = "Sørfugl", count = 100) // less common nationally
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
        assumeTrue(isNorwayExport) // labels are locale-specific (Bokmål here); Sweden renders Swedish
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

    @Test
    fun shortAgeAbbreviatesWordyAgesAndLeavesKAges() {
        assertEquals("Ad", shortAge("Adult"))
        assertEquals("Pu", shortAge("Pulli"))
        assertEquals("Eg", shortAge("Egg"))
        assertEquals("2K+", shortAge("2K+")) // K-ages are already short - passed through
        // Sweden's lower-case vocabulary abbreviates too (#155), so the preview isn't "adult".
        assertEquals("ad", shortAge("adult"))
        assertEquals("pu", shortAge("pulli"))
        assertEquals("äg", shortAge("ägg"))
    }

    @Test
    fun sexSymbolKeepsVariationSelectorForTextPresentation() {
        // The list-row preview shows ♂/♀ as symbols. Each must carry a trailing VS15 (U+FE0E) to
        // force *text* presentation - without it Android renders them as tall colour emoji that
        // inflate the row height. The selector is invisible in source, so a "cleanup" could silently
        // drop it; spell the codepoints out so this test can't be corrupted the same way. Hunnfarget
        // keeps its parens around the selector-bearing symbol.
        assertEquals("\u2642\uFE0E", sexSymbol("Hann")) // ♂ + VS15
        assertEquals("\u2640\uFE0E", sexSymbol("Hunn")) // ♀ + VS15
        assertEquals("(\u2640\uFE0E)", sexSymbol("Hunnfarget"))
        assertEquals("\u2642\uFE0E\u2640\uFE0E", sexSymbol("I par"))
        // Sweden's sex vocabulary maps to the same symbols (#155).
        assertEquals("\u2642\uFE0E", sexSymbol("Hane"))
        assertEquals("\u2640\uFE0E", sexSymbol("Hona"))
        assertEquals("(\u2640\uFE0E)", sexSymbol("Honfärgad"))
    }
}
