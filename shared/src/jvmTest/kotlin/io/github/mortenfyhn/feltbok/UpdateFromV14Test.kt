package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** An update must keep the user's files. resources/v1.4/ holds every file the app keeps, written
 *  on a phone by v1.4's own save code, and this loads them through today's load functions, by the
 *  same file names. When a file format changes, add the old format's files here instead of
 *  replacing these. */
class UpdateFromV14Test {

    private val storage = object : Storage {
        val files = listOf(
            "notes.json", "archive.jsonl", "uses.json", "act_uses.json", "coobs_uses.json", "coobs_users.json",
            "party.json", "lang_prefs.json", "my-localities.csv", "live-localities-18.csv",
        ).associateWith { javaClass.getResource("/v1.4/$it")!!.readText() }.toMutableMap()

        override fun exists(name: String) = name in files
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun append(name: String, text: String) { files[name] = files[name].orEmpty() + text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.toList()
        override fun readAsset(name: String): String? = null
    }

    private val full = Note(
        id = 1_717_000_000_000, time = 1_717_000_060_000, endTime = 1_717_003_600_000, timeUnknown = true,
        species = "Gråmåke", latin = "Larus argentatus", count = 3, age = "Adult",
        activity = "Næringssøkende", sex = "Hann", publicComment = "1/2 på \"sjøen\"\nmed unger, 100%",
        privateComment = "notat\tmed tab", locName = "Titran", locFull = "Titran, Frøya, Trøndelag",
        lat = 63.67, lon = 8.31, newLoc = true, locRadius = 25, uncertain = true,
        coObservers = listOf("Kari Nordmann", "Ibe55"), kommune = "Frøya", hideUntil = 1_720_000_000_000,
    )
    private val minimal = Note(
        id = 1_717_100_000_000, species = "Kjøttmeis", latin = "Parus major", count = 1, age = "",
        activity = "", sex = "", publicComment = "", privateComment = "", locName = "Hagen",
        locFull = "Hagen", lat = 63.0, lon = 10.0,
    )

    @Test
    fun notesAndArchive() {
        val unknownCount = minimal.copy(
            id = 1_717_200_000_000, species = "ub. måke", latin = "Larus sp.", count = UNKNOWN_COUNT,
            lat = -1.5, lon = -0.25,
        )
        assertEquals(listOf(full, minimal, unknownCount), loadNotes(storage))
        assertEquals(
            listOf(minimal.copy(id = 1_700_000_000_000, publicComment = "arkivert"), full.copy(id = 1_700_000_000_001)),
            loadArchive(storage),
        )
    }

    @Test
    fun settings() {
        val uses = loadUses(storage, now = 1_717_000_000_000)
        assertEquals(UseEntry(2.5, 1_716_000_000_000), uses["Parus major"])
        assertEquals(UseEntry(1.0, 1_717_000_000_000), uses["Pica pica"])
        assertEquals(mapOf("Næringssøkende" to 4, "Rastende" to 1), loadActUses(storage))
        assertEquals(mapOf("Kari Nordmann" to 3, "Ibe55" to 2), loadCoObsUses(storage))
        assertEquals(
            mapOf("Ibe55" to AoUser("Inge Berg", "Ibe55", "Trondheim"), "kari1" to AoUser("Kari Nordmann", "kari1", "")),
            loadCoObsUsers(storage),
        )
        assertEquals(listOf("Kari Nordmann", "Ibe55"), loadParty(storage))
        assertEquals(LangPrefs(Lang.SVENSK, Lang.LATIN, true), loadLangPrefs(storage, LangPrefs(Lang.NORSK, Lang.LATIN)))
    }

    /** The user's own localities survive; the live ones fetched under v1.4 are dropped, by design. */
    @Test
    fun localities() {
        val locs = loadLocalities(storage, versionCode = 19)
        assertEquals(listOf("123", "456"), locs.map { it.id })
        val poly = locs[0]
        assertEquals("Mitt, \"hemmelige\" sted", poly.lokalitet)
        assertEquals(listOf(listOf(63.43, 10.39), listOf(63.44, 10.39), listOf(63.44, 10.4)), poly.polygon.map { it.toList() })
        assertEquals(Triple(50.0, false, true), Triple(poly.radius, poly.public, poly.mine))
        assertEquals(Locality("456", "Bakgården", "", "Trondheim", 63.42, 10.38, 0, 25.0, public = false, mine = true), locs[1])
        assertFalse(storage.exists("live-localities-18.csv"))
    }
}
