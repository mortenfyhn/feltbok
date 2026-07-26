package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** The archive's JSON Lines format (#153): one note per line, so archiving appends without
 *  parsing the existing file. Round-trip through the same per-field mapping as notes.json,
 *  and a torn/corrupt line (a crash mid-append) must not take the rest of the archive with it. */
class ArchiveTest {

    private val full = Note(
        id = 1_700_000_000_000, time = 1_700_000_100_000, endTime = 1_700_000_200_000,
        timeUnknown = true, species = "Gråmåke", latin = "Larus argentatus", count = 3,
        age = "Adult", activity = "Næringssøkende", sex = "Hann",
        publicComment = "på sjøen", privateComment = "notat",
        locName = "Titran", locFull = "Titran, Frøya, Trøndelag", lat = 63.67, lon = 8.31,
        newLoc = true, locRadius = 25, uncertain = true,
        coObservers = listOf("Kari Nordmann", "Ola Nordmann"), kommune = "Frøya",
    )
    private val minimal = Note(
        id = 2, species = "kjøttmeis", latin = "Parus major", count = 1,
        age = "", activity = "", sex = "", publicComment = "", privateComment = "",
        locName = "", locFull = "", lat = 0.0, lon = 0.0,
    )

    @Test
    fun jsonlRoundTripsEveryField() {
        val notes = listOf(full, minimal)
        assertEquals(notes, notesFromJsonl(notesToJsonl(notes)))
    }

    @Test
    fun appendedBatchesConcatenate() {
        // Archiving appends to the existing file, so two serialized batches back to back
        // must read back as one list.
        val text = notesToJsonl(listOf(full)) + notesToJsonl(listOf(minimal))
        assertEquals(listOf(full, minimal), notesFromJsonl(text))
    }

    @Test
    fun corruptLineIsSkippedNotFatal() {
        val text = notesToJsonl(listOf(full)) + "{\"id\": 42, \"trunc\n" + notesToJsonl(listOf(minimal))
        assertEquals(listOf(full, minimal), notesFromJsonl(text))
    }

    @Test
    fun emptyTextYieldsEmptyList() {
        assertEquals(emptyList<Note>(), notesFromJsonl(""))
    }
}
