package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** notes.json as the shipped app writes it, so a change to the JSON code can't strand the notes
 *  already on people's phones. The fixture mimics Android's org.json output: keys in insertion
 *  order, "/" escaped as "\/", non-ASCII left raw, whole doubles written without ".0". */
class NotesFileTest {

    private val androidNotesJson = """
        [{"id":1717000000000,"time":1717000060000,"species":"Gråmåke","latin":"Larus argentatus","endTime":1717003600000,"timeUnknown":true,"count":3,"age":"Adult","activity":"Næringssøkende","sex":"Hann","publicComment":"1\/2 på \"sjøen\"\nmed unger","privateComment":"notat","locName":"Titran","locFull":"Titran, Frøya, Trøndelag","lat":63.67,"lon":8.31,"newLoc":true,"locRadius":25,"uncertain":true,"coObservers":["Kari Nordmann","ola123"],"kommune":"Frøya","hideUntil":1720000000000},
        {"id":1600000000000,"species":"Kjøttmeis","count":1,"age":"","activity":"","sex":"","publicComment":"","privateComment":"","locName":"Hagen","locFull":"","lat":63,"lon":10},
        {"id":1717000000000,"time":1717000000000,"species":"Ubestemt","latin":"","count":-1,"age":"","activity":"","sex":"","publicComment":"","privateComment":"","locName":"","locFull":"","lat":-1.5,"lon":-0.25,"newLoc":false,"locRadius":0,"uncertain":false}]
    """.trimIndent()

    @Test
    fun loadsNotesWrittenByTheAndroidApp() {
        val full = Note(
            id = 1717000000000, time = 1717000060000, endTime = 1717003600000, timeUnknown = true,
            species = "Gråmåke", latin = "Larus argentatus", count = 3, age = "Adult",
            activity = "Næringssøkende", sex = "Hann", publicComment = "1/2 på \"sjøen\"\nmed unger",
            privateComment = "notat", locName = "Titran", locFull = "Titran, Frøya, Trøndelag",
            lat = 63.67, lon = 8.31, newLoc = true, locRadius = 25, uncertain = true,
            coObservers = listOf("Kari Nordmann", "ola123"), kommune = "Frøya", hideUntil = 1720000000000,
        )
        // Saved before time, latin and the later optional fields existed: time falls back to id.
        val legacy = Note(
            id = 1600000000000, species = "Kjøttmeis", latin = "", count = 1, age = "", activity = "",
            sex = "", publicComment = "", privateComment = "", locName = "Hagen", locFull = "",
            lat = 63.0, lon = 10.0,
        )
        // Same id as the first note: healed to the next free millisecond, not dropped.
        val unknownCount = Note(
            id = 1717000000001, time = 1717000000000, species = "Ubestemt", latin = "", count = UNKNOWN_COUNT,
            age = "", activity = "", sex = "", publicComment = "", privateComment = "", locName = "",
            locFull = "", lat = -1.5, lon = -0.25,
        )
        assertEquals(listOf(full, legacy, unknownCount), parseNotes(androidNotesJson))
    }

    @Test
    fun savedNotesLoadBack() {
        val notes = parseNotes(androidNotesJson)
        assertEquals(notes, parseNotes(notesJson(notes)))
    }
}
