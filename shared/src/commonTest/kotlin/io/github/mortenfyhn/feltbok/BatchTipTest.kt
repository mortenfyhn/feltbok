package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatchTipTest {
    private fun note(id: Long, locName: String = "Titran", count: Int = 1, pub: String = "") = Note(
        id = id, species = "Gråmåke", latin = "Larus argentatus", count = count, age = "", activity = "",
        sex = "", publicComment = pub, privateComment = "", locName = locName, locFull = "", lat = 63.0, lon = 8.0,
    )

    private fun edit(id: Long, before: Note, after: Note) = NoteEdit(id, batchableChanges(before, after))

    @Test
    fun theSameChangeOnAnotherNoteRepeats() {
        val first = edit(1, note(1), note(1, locName = "Fornebu"))
        val second = edit(2, note(2, count = 3), note(2, count = 3, locName = "Fornebu"))
        assertTrue(repeatsEdit(first, second))
    }

    @Test
    fun aDifferentValueOrFieldDoesNotRepeat() {
        val first = edit(1, note(1), note(1, locName = "Fornebu"))
        assertFalse(repeatsEdit(first, edit(2, note(2), note(2, locName = "Titran sør"))))
        assertFalse(repeatsEdit(first, edit(2, note(2), note(2, count = 5))))
    }

    @Test
    fun editingTheSameNoteAgainDoesNotRepeat() {
        val first = edit(1, note(1), note(1, locName = "Fornebu"))
        assertFalse(repeatsEdit(first, first))
    }

    @Test
    fun commentsAreNotBatchable() {
        assertEquals(emptySet(), batchableChanges(note(1), note(1, pub = "flygende")))
    }
}
