package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search behaviour: names and languages, and the ranking promises the user sees. Moved from
 *  ModelTest with the search code into the shared module. */
class SearchTest {
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
}
