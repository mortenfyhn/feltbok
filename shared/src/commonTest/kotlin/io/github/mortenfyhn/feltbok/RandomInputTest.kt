package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The search and the selection logic with random input (#201). */
class RandomInputTest {
    private val species = listOf(
        Species("Parus major", "kjøttmeis", "talgoxe", count = 900),
        Species("Larus argentatus", "gråmåke", "gråtrut", "VU", 500),
        Species("Pica pica", "skjære", "skata", count = 700),
        Species("Larus sp.", "ub. måke", otherTaxon = true),
        Species("Aix galericulata", "mandarinand", ""),
    )
    private val prepared = prepare(species)

    @Test
    fun searchTakesAnyQuery() = forSeeds(1000) {
        val query = oneOf(string(), string(500), "æ", " ", "-", "ub.", "kjøttmeis".take(r.nextInt(10)))
        assertTrue(rankSpecies(query, prepared, { 0.5 }).all { it.species in species })
    }

    @Test
    fun sweepCoversTheRowsBetween() = forSeeds {
        val ids = List(r.nextInt(10)) { it }
        val a = r.nextInt(-2, 12); val b = r.nextInt(-2, 12)
        val swept = sweepRange(ids, a, b)
        val expected = if (a in ids.indices && b in ids.indices) (minOf(a, b)..maxOf(a, b)).toList() else emptyList()
        assertEquals(expected, swept)
    }

    @Test
    fun aDayToggleMarksOrClearsTheWholeDay() = forSeeds {
        val selected = List(r.nextInt(6)) { r.nextLong(10) }.toSet()
        val day = List(r.nextInt(6)) { r.nextLong(10) }.distinct()
        val after = toggledDay(selected, day)
        if (day.isNotEmpty() && selected.containsAll(day)) assertTrue(day.none { it in after }) else assertTrue(after.containsAll(day))
        assertEquals(selected - day.toSet(), after - day.toSet()) // other days untouched
    }

    @Test
    fun aBatchEditTouchesOnlyTheMarkedNotes() = forSeeds {
        val notes = notes()
        val ids = notes.filter { r.nextBoolean() }.map { it.id }.toSet() + r.nextLong()
        val change = BatchChange(
            count = orNull(r.nextInt(100)), age = orNull(string()), coObservers = orNull(listOf(string())),
            time = orNull(BatchTime(r.nextLong(0, 1L shl 41), null)), hideUntil = orNull(BatchHide(null)),
        )
        val after = applyBatchEdit(notes, ids, change)
        assertEquals(notes.map { it.id }, after.map { it.id })
        notes.zip(after).forEach { (n, m) ->
            if (n.id !in ids || change.isNoOp) assertEquals(n, m)
            else assertEquals(n.copy(species = m.species, count = m.count, age = m.age, coObservers = m.coObservers, time = m.time, endTime = m.endTime, timeUnknown = m.timeUnknown, hideUntil = m.hideUntil), m)
        }
    }
}
