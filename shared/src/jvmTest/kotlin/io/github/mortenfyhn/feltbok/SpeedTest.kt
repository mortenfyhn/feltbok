package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Times the slowest work on the full bundled data (#201). The limits are loose, about 10x what a
 *  laptop takes, so only a large slowdown fails, like a loop that turned quadratic. */
class SpeedTest {
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

    private fun within(limit: Duration, what: String, took: Duration) =
        assertTrue("$what took $took, limit $limit", took < limit)

    /** A search runs on every keystroke, over every name of every species and taxon. */
    @Test
    fun searchIsFast() {
        val species = loadSpecies(storage) + loadOtherTaxa(storage)
        val prepared = prepare(species)
        val freq = NumericFrequency(species)
        val queries = listOf("k", "kj", "kjø", "kjøttm", "gråmåke", "ub", "larus", "xqzw", "sitronsommerfugl", "a")
        val searchAll = { queries.map { q -> rankSpecies(q, prepared, freq::weight).size } }
        repeat(3) { val _ = searchAll() } // warm up the JIT
        within(25.milliseconds, "A search", measureTimedValue(searchAll).duration / queries.size) // 2 ms on a laptop
    }

    /** Both run at every launch, on all ~80k localities, before the map can show them. */
    @Test
    fun localitiesLoadFast() {
        val (locs, load) = measureTimedValue { loadLocalities(storage, 1) }
        within(4.seconds, "Loading the localities", load) // 0.35 s on a laptop, cold
        within(1.seconds, "Building the map's grid", measureTimedValue { LocalityGrid(locs) }.duration) // 0.07 s
    }
}
