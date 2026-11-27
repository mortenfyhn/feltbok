package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app's files, damaged at random (#201). Loading must not crash, and damage to one note must
 *  not lose the others. */
class DamagedFilesTest {
    private class MemStorage(val files: MutableMap<String, String> = mutableMapOf()) : Storage {
        override fun exists(name: String) = name in files
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun append(name: String, text: String) { files[name] = files[name].orEmpty() + text }
        override fun delete(name: String) { files.remove(name) }
        override fun list() = files.keys.toList()
        override fun readAsset(name: String): String? = null
    }

    /** A storage holding every file the app keeps, written by the app's own save code. */
    private fun Gen.filled(): MemStorage = MemStorage().also { s ->
        saveNotes(s, notes())
        appendToArchive(s, notes())
        saveUses(s, mapOf(string() to UseEntry(r.nextDouble(), r.nextLong())))
        saveActUses(s, mapOf(string() to r.nextInt()))
        saveCoObsUses(s, mapOf(string() to r.nextInt()))
        saveCoObsUsers(s, listOf(AoUser(string(), string(), string(), orNull(r.nextLong()))))
        saveParty(s, listOf(string(), string()))
        saveLangPrefs(s, LangPrefs(Lang.SVENSK, Lang.LATIN, true))
        val loc = Locality("123", string(), "", string(), 63.4, 10.4, 0, 50.0, listOf(doubleArrayOf(63.4, 10.4), doubleArrayOf(63.5, 10.4)), public = false, mine = true)
        saveMyLocalities(s, listOf(loc))
        appendLiveLocalities(s, VERSION, listOf(loc.copy(id = "456", public = true, mine = false)))
    }

    private fun loadAll(s: Storage) = listOf(
        loadNotes(s), loadArchive(s), loadUses(s), loadActUses(s), loadCoObsUses(s), loadCoObsUsers(s),
        loadParty(s), loadLangPrefs(s, LangPrefs(Lang.NORSK, Lang.LATIN)), loadLocalities(s, VERSION),
    )

    @Test
    fun aDamagedFileDoesNotCrashTheLoad() = forSeeds(500) {
        val s = filled()
        val name = s.files.keys.random(r)
        s.files[name] = damage(s.files.getValue(name))
        val _ = loadAll(s)
    }

    // The locality files are the only ones parsed by hand, so they get more cases.
    @Test
    fun aDamagedLocalityFileDoesNotCrashTheLoad() = forSeeds(2000) {
        val s = filled()
        val name = oneOf("my-localities.csv", "live-localities-$VERSION.csv")
        s.files[name] = damage(s.files.getValue(name))
        val _ = loadLocalities(s, VERSION)
    }

    @Test
    fun aBadNoteDoesNotLoseTheOthers() = forSeeds {
        val notes = notes().ifEmpty { listOf(note()) }
        val bad = notes.indices.random(r)
        // A required field gone, as a bug or a hand edit could leave it.
        val objects = notes.map { noteToJson(it).toString() }.toMutableList()
        objects[bad] = objects[bad].replace(oneOf(""""species":""", """"count":""", """"lat":"""), """"x":""")
        val s = MemStorage(mutableMapOf("notes.json" to objects.joinToString(",", "[", "]")))
        assertEquals(notes.filterIndexed { i, _ -> i != bad }, loadNotes(s))
        assertKept(s, objects.joinToString(",", "[", "]"))
    }

    /** The next save writes the notes that loaded over notes.json, so the damaged file is kept
     *  beside it, for a later version or a developer to recover. */
    @Test
    fun aDamagedNotesFileIsKept() = forSeeds {
        val text = damage(notesJson(notes().ifEmpty { listOf(note()) }))
        val s = MemStorage(mutableMapOf("notes.json" to text))
        val loaded = loadNotes(s)
        if (runCatching { parseNotes(text) }.isFailure) {
            assertKept(s, text)
            saveNotes(s, loaded)
            assertKept(s, text)
        }
    }

    private fun assertKept(s: MemStorage, text: String) =
        assertTrue(s.files.any { (name, t) -> name.startsWith("notes-damaged-") && t == text }, "Not kept: $text")

    @Test
    fun aDamagedArchiveLineLosesOnlyThatNote() = forSeeds {
        val notes = notes().ifEmpty { listOf(note()) }
        val lines = notesToJsonl(notes).lines().filter { it.isNotEmpty() }.toMutableList()
        val bad = lines.indices.random(r)
        lines[bad] = damage(lines[bad]).replace("\n", "")
        val loaded = loadArchive(MemStorage(mutableMapOf("archive.jsonl" to lines.joinToString("\n"))))
        assertTrue(loaded.containsAll(notes.filterIndexed { i, _ -> i != bad }))
    }

    private companion object {
        const val VERSION = 30
    }
}
