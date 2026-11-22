package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The bundled other_taxa.csv (#190) against the bundled checklist: the many rare taxa must not
 *  push a species off the top of its own name's search. */
class OtherTaxaTest {

    private val assets = listOf("../app/src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }

    private val storage = object : Storage {
        override fun exists(name: String) = false
        override fun read(name: String): String? = null
        override fun write(name: String, text: String) = Unit
        override fun append(name: String, text: String) = Unit
        override fun delete(name: String) = Unit
        override fun list() = emptyList<String>()
        override fun readAsset(name: String) = File(assets, name).takeIf { it.exists() }?.readText()
    }

    private val species = loadSpecies(storage)
    private val others = loadOtherTaxa(storage)
    private val all = species + others
    private val freq = NumericFrequency(all)

    @Test
    fun everySpeciesComesFirstForItsOwnName() {
        val prepared = prepare(all, setOf(Lang.NORSK))
        val wrong = species.filter { s -> rankSpecies(s.norsk, prepared, freq::weight).first().species != s }
        assertEquals(emptyList<Species>(), wrong)
    }

    private fun top(query: String, from: List<Species>, n: Int = 5) =
        rankSpecies(query, prepare(from, setOf(Lang.NORSK)), freq::weight).map { it.species.norsk }.distinct().take(n)

    /** A partial name is a prefix of many taxa ("måk" -> måkefamilien) but often only a suffix of the
     *  species (hettemåke). Without the demotion these pushed species out of the top 3. Further down,
     *  a taxon may beat a species that matched only on scattered letters (lin: linerle, underarten
     *  alba before langhalefink). */
    @Test
    fun otherTaxaDontCrowdOutSpecies() {
        for (q in listOf("ær", "måk", "and", "sm", "lin", "spu", "tj", "fu")) assertEquals(q, top(q, species, 3), top(q, all, 3))
    }

    /** Also lists every hybrid on "stokkand x", though the species matches it too. */
    @Test
    fun otherTaxonComesFirstForItsFullName() {
        val prepared = prepare(all, setOf(Lang.NORSK))
        val wrong = others.filter { s -> rankSpecies(s.norsk, prepared, freq::weight).first().species != s }
        assertEquals(emptyList<Species>(), wrong)
        val hybrids = others.map { it.norsk }.filter { it.startsWith("stokkand x") }.toSet()
        assertEquals(hybrids, top("stokkand x", all, hybrids.size).toSet())
    }

    @Test
    fun otherTaxaHaveTaxonIds() {
        val ids = loadTaxonIds(storage)
        assertEquals(emptyList<String>(), others.map { it.latin }.filter { it !in ids })
        assertEquals(299710L, ids["Corvus corone cornix"]) // gråkråke
    }
}
