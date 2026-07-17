package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the selection + batch-edit logic (#120) - the logic-heavy core that a regression
 *  would break silently. Pure functions in SelectionLogic.kt; the ViewModel just calls them. */
class SelectionLogicTest {

    private fun note(
        id: Long,
        species: String = "Gråmåke",
        latin: String = "Larus argentatus",
        count: Int = 1,
        age: String = "",
        activity: String = "",
        sex: String = "",
        pub: String = "",
        priv: String = "",
        locName: String = "Titran",
        locFull: String = "Titran, Frøya",
        lat: Double = 63.0,
        lon: Double = 8.0,
        newLoc: Boolean = false,
        locRadius: Int = 0,
        kommune: String = "Frøya",
    ) = Note(
        id = id, species = species, latin = latin, count = count,
        age = age, activity = activity, sex = sex, publicComment = pub, privateComment = priv,
        locName = locName, locFull = locFull, lat = lat, lon = lon,
        newLoc = newLoc, locRadius = locRadius, kommune = kommune,
    )

    private fun loc(
        name: String,
        lat: Double = 60.0,
        lon: Double = 10.0,
        kommune: String = "Oslo",
        newLoc: Boolean = false,
        radius: Double = 0.0,
    ) = Locality("", name, "", kommune, lat, lon, 0, radius, newLoc = newLoc)

    // ---- applyBatchEdit ----

    @Test
    fun onlyTargetedNotesChange() {
        val notes = listOf(note(1), note(2), note(3))
        val out = applyBatchEdit(notes, setOf(1L, 3L), BatchChange(age = "Adult"))
        assertEquals("Adult", out[0].age)
        assertEquals("", out[1].age)            // id 2 not targeted
        assertEquals("Adult", out[2].age)
        assertSame("untargeted note passes through unchanged (same instance)", notes[1], out[1])
    }

    @Test
    fun onlySpecifiedFieldsChange() {
        val n = note(1, count = 5, sex = "Hann", activity = "Næringssøk")
        val out = applyBatchEdit(listOf(n), setOf(1L), BatchChange(age = "1K")).single()
        assertEquals("1K", out.age)
        // Everything else is preserved.
        assertEquals(5, out.count)
        assertEquals("Hann", out.sex)
        assertEquals("Næringssøk", out.activity)
        assertEquals(n.species, out.species)
        assertEquals(n.locName, out.locName)
    }

    @Test
    fun multipleFieldsAtOnce() {
        val out = applyBatchEdit(listOf(note(1)), setOf(1L), BatchChange(age = "Adult", sex = "Hunn", count = 4)).single()
        assertEquals("Adult", out.age)
        assertEquals("Hunn", out.sex)
        assertEquals(4, out.count)
    }

    @Test
    fun speciesPickMovesBothNames() {
        val out = applyBatchEdit(listOf(note(1)), setOf(1L), BatchChange(species = SpeciesPick("Fiskemåke", "Larus canus"))).single()
        assertEquals("Fiskemåke", out.species)
        assertEquals("Larus canus", out.latin)
    }

    @Test
    fun localityMapsAllFieldsAndClearsLocFull() {
        val n = note(1, locName = "Titran", locFull = "Titran, Frøya", lat = 63.0, lon = 8.0, kommune = "Frøya")
        val out = applyBatchEdit(listOf(n), setOf(1L), BatchChange(locality = loc("Fornebu", lat = 59.9, lon = 10.6, kommune = "Bærum"))).single()
        assertEquals("Fornebu", out.locName)
        assertEquals("", out.locFull)          // qualified name cleared, as commitDraft does
        assertEquals(59.9, out.lat, 0.0)
        assertEquals(10.6, out.lon, 0.0)
        assertEquals("Bærum", out.kommune)
        assertTrue(!out.newLoc)
        assertEquals(0, out.locRadius)
    }

    @Test
    fun newLocalityCarriesRadius() {
        val out = applyBatchEdit(listOf(note(1)), setOf(1L), BatchChange(locality = loc("Ny plass", newLoc = true, radius = 120.0))).single()
        assertTrue(out.newLoc)
        assertEquals(120, out.locRadius)
    }

    @Test
    fun timeSetsBothEndpoints() {
        val n = note(1).copy(time = 100, endTime = 200)
        val out = applyBatchEdit(listOf(n), setOf(1L), BatchChange(time = BatchTime(500, null))).single()
        assertEquals(500L, out.time)
        assertEquals(null, out.endTime)
    }

    @Test
    fun idIsNeverChangedByBatch() {
        val out = applyBatchEdit(listOf(note(7)), setOf(7L), BatchChange(time = BatchTime(999, 1000), age = "Adult")).single()
        assertEquals(7L, out.id)   // id is the stable key - a time edit must not touch it
    }

    @Test
    fun noOpDetection() {
        assertTrue(BatchChange().isNoOp)
        assertTrue(!BatchChange(age = "").isNoOp)   // clearing age is a real change, not a no-op
        assertTrue(!BatchChange(time = BatchTime(1, null)).isNoOp)
    }

    @Test
    fun blankValueClearsField() {
        val out = applyBatchEdit(listOf(note(1, age = "Adult")), setOf(1L), BatchChange(age = "")).single()
        assertEquals("", out.age)
    }

    @Test
    fun emptyIdsChangeNothing() {
        val notes = listOf(note(1), note(2))
        val out = applyBatchEdit(notes, emptySet(), BatchChange(age = "Adult"))
        assertEquals(notes, out)
    }

    @Test
    fun emptyChangeIsNoOpEvenWhenTargeted() {
        val n = note(1, age = "Adult", count = 3)
        val out = applyBatchEdit(listOf(n), setOf(1L), BatchChange()).single()
        assertEquals(n, out)
    }

    @Test
    fun orderIsPreserved() {
        val notes = listOf(note(3), note(1), note(2))   // deliberately unsorted
        val out = applyBatchEdit(notes, setOf(1L, 2L, 3L), BatchChange(sex = "Hann"))
        assertEquals(listOf(3L, 1L, 2L), out.map { it.id })
    }

    @Test
    fun targetedNoteIsANewInstance() {
        val notes = listOf(note(1))
        val out = applyBatchEdit(notes, setOf(1L), BatchChange(age = "Adult"))
        assertNotSame(notes[0], out[0])
    }

    // ---- batchFieldPreview ----

    @Test
    fun previewSharedValue() {
        assertEquals("Adult", batchFieldPreview(listOf("Adult", "Adult", "Adult")))
    }

    @Test
    fun previewSingle() {
        assertEquals("Titran", batchFieldPreview(listOf("Titran")))
    }

    @Test
    fun previewAllBlank() {
        assertEquals("", batchFieldPreview(listOf("", "", "")))
    }

    @Test
    fun previewMixedJoinsDistinctNonBlank() {
        assertEquals("Titran, Fornebu", batchFieldPreview(listOf("Titran", "Fornebu", "Titran")))
    }

    @Test
    fun previewMixedDropsBlanks() {
        assertEquals("Adult", batchFieldPreview(listOf("Adult", "", "Adult")))
    }

    @Test
    fun previewEmptyList() {
        assertEquals("", batchFieldPreview(emptyList()))
    }

    // ---- toggledDay ----

    @Test
    fun toggleDayFromNoneMarksAll() {
        assertEquals(setOf(1L, 2L, 3L), toggledDay(emptySet(), listOf(1, 2, 3)))
    }

    @Test
    fun toggleDayPartialCompletesTheDay() {
        assertEquals(setOf(1L, 2L, 3L), toggledDay(setOf(2L), listOf(1, 2, 3)))
    }

    @Test
    fun toggleDayFullyMarkedClearsTheDay() {
        assertEquals(emptySet<Long>(), toggledDay(setOf(1L, 2L, 3L), listOf(1, 2, 3)))
    }

    @Test
    fun toggleDayLeavesOtherDaysAlone() {
        // Day {1,2} fully marked plus an unrelated mark 9: toggling the day clears only 1,2.
        assertEquals(setOf(9L), toggledDay(setOf(1L, 2L, 9L), listOf(1, 2)))
        // Adding a day keeps the unrelated mark.
        assertEquals(setOf(1L, 2L, 9L), toggledDay(setOf(9L), listOf(1, 2)))
    }

    // ---- sweepRange ----

    @Test
    fun sweepInclusiveDownward() {
        assertEquals(listOf(10L, 11L, 12L), sweepRange(listOf(10, 11, 12, 13), 0, 2))
    }

    @Test
    fun sweepInclusiveUpwardSameRange() {
        assertEquals(listOf(10L, 11L, 12L), sweepRange(listOf(10, 11, 12, 13), 2, 0))
    }

    @Test
    fun sweepSingleRow() {
        assertEquals(listOf(11L), sweepRange(listOf(10, 11, 12), 1, 1))
    }

    @Test
    fun sweepOffListIsEmpty() {
        assertEquals(emptyList<Long>(), sweepRange(listOf(10, 11), -1, 1))
        assertEquals(emptyList<Long>(), sweepRange(listOf(10, 11), 0, 5))
    }
}
