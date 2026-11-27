package io.github.mortenfyhn.feltbok

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Random notes, with every field set to odd values, through the app's file formats and the
 *  upload (#201). */
class RandomNotesTest {
    // Files are written as UTF-8, so the round trip goes through bytes too.
    private fun viaFile(text: String) = text.encodeToByteArray().decodeToString()

    @Test
    fun notesJsonKeepsEveryField() = forSeeds {
        val notes = notes()
        assertEquals(notes, parseNotes(viaFile(notesJson(notes))))
    }

    @Test
    fun archiveKeepsEveryField() = forSeeds {
        val notes = notes()
        assertEquals(notes, notesFromJsonl(viaFile(notesToJsonl(notes))))
    }

    @Test
    fun uploadBodyCarriesTheNote() = forSeeds {
        val n = note()
        val _ = uploadProblem(n, now = r.nextLong(0, 4_102_444_800_000), zone = TimeZone.UTC) // must not throw
        val body = Json.parseToJsonElement(sightingJson(n, 3681, listOf(1, 2), zone = TimeZone.UTC)).jsonObject
        assertEquals(n.publicComment, body["comment"]!!.jsonPrimitive.content)
        assertEquals(n.privateComment, body["privateComment"]!!.jsonPrimitive.content)
        if (n.count == UNKNOWN_COUNT) assertEquals(JsonNull, body["quantity"]) else assertEquals(n.count, body["quantity"]!!.jsonPrimitive.int)
    }
}
