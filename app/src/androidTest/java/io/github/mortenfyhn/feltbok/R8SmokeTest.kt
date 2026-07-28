package io.github.mortenfyhn.feltbok

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke tests that run on a real device against the **minified** releaseTest
 * variant (see app/build.gradle.kts `testBuildType`). The point is NOT to re-test the pure
 * logic — `ModelTest` already does that on the JVM — but to prove these reflection-/runtime-
 * sensitive paths survive R8 tree-shaking + ART: the real `org.json` (not the JVM test shim),
 * `String.format` locales, and reading the bundled CSV assets through a real `Context`.
 *
 * Run via `just itest` (device-gated). Follow-up to #113 / #117.
 */
@RunWith(AndroidJUnit4::class)
class R8SmokeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sampleNote(ms: Long = 1_717_233_000_000L) = Note(
        id = ms, species = "Gråmåke", latin = "Larus argentatus", count = 3,
        age = "Adult", activity = "Næringssøk", sex = "Hann",
        publicComment = "på sjøen", privateComment = "test",
        locName = "Titran", locFull = "Titran, Frøya, Tø", lat = 63.67, lon = 8.31,
    )

    /** noteToJson/noteFromJson use android's real org.json on ART; a round-trip proves R8 kept
     *  the JSON paths and the field mapping survives. */
    @Test
    fun noteJsonRoundTrips() {
        val n = sampleNote()
        assertEquals(n, noteFromJson(noteToJson(n)))
    }

    /** TSV export exercises String.format(Locale.US, …) + the column layout on the device. */
    @Test
    fun tsvExportHasHeaderAndRow() {
        val lines = exportTsv(listOf(sampleNote())).split("\n")
        assertEquals(2, lines.size)
        assertEquals(16, lines[1].split("\t").size)
        assertEquals("Gråmåke", lines[1].split("\t")[0])
    }

    /** loadLocalities reads the bundled localities.csv asset and parses WKT polygons; proves
     *  R8/resource-shrinking kept the asset and the CSV parser works on the device. */
    @Test
    fun bundledLocalitiesLoad() {
        val locs = loadLocalities(ctx)
        assertTrue("expected bundled localities", locs.isNotEmpty())
        assertTrue("expected named localities", locs.all { it.lokalitet.isNotBlank() })
    }

    /** The bundled species checklist loads and parses. */
    @Test
    fun bundledSpeciesLoad() {
        val species = loadSpecies(ctx)
        assertTrue("expected bundled species", species.isNotEmpty())
    }

    /** ubestemt.csv (#162) only exists in the Norway flavor's assets, so this proves R8/resource
     *  shrinking + flavor packaging actually ship it into the built APK. */
    @Test
    fun bundledUbestemtLoads() {
        val ubestemt = loadUbestemt(ctx)
        assertTrue("expected bundled ub. entries", ubestemt.isNotEmpty())
    }
}
