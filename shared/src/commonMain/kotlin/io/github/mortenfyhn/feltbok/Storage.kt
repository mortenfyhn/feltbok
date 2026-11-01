package io.github.mortenfyhn.feltbok

import kotlin.time.Clock

/** The app's files by name, implemented per platform (on Android: internal storage, the APK's
 *  assets, and the external dir `adb push` can reach). */
interface Storage {
    fun exists(name: String): Boolean

    /** A private file's text, or null when it doesn't exist. */
    fun read(name: String): String?

    /** Replace a private file atomically: a crash mid-save leaves the old content or the new, never
     *  an empty or torn file - unrecoverable for field notes (#153). */
    fun write(name: String, text: String)

    fun append(name: String, text: String)

    fun delete(name: String)

    /** The names of all private files. */
    fun list(): List<String>

    /** A bundled data file (a CSV), or null when this build ships none. */
    fun readAsset(name: String): String?

    /** A file a developer pushed to the device (`just push-data`), or null. */
    fun readPushed(name: String): String? = null
}

// ---- note persistence (a JSON file in app storage) ----

private const val NOTES = "notes.json"

fun loadNotes(storage: Storage): List<Note> =
    runCatching { storage.read(NOTES)?.let(::parseNotes) }.getOrNull() ?: emptyList()

fun saveNotes(storage: Storage, notes: List<Note>) = storage.write(NOTES, notesJson(notes))

// ---- archive file (#153): where "deleted" observations go, restorable from the archive screen.
// Separate from notes.json so the hot paths (launch parse, every-save rewrite, the Downloads TSV
// mirror) stay proportional to the LIVE notes however many years of archive pile up. ----

private const val ARCHIVE = "archive.jsonl"

/** Archiving appends - it never parses the existing archive (the point of JSONL). A torn append
 *  can only corrupt the final line, which [notesFromJsonl] skips, so no atomic dance is needed. */
fun appendToArchive(storage: Storage, notes: List<Note>) {
    if (notes.isEmpty()) return
    storage.append(ARCHIVE, notesToJsonl(notes))
}

fun loadArchive(storage: Storage): List<Note> =
    runCatching { storage.read(ARCHIVE)?.let(::notesFromJsonl) }.getOrNull() ?: emptyList()

/** Full rewrite - only shrinking the archive (a restore, or an undone archiving) needs this.
 *  An empty archive deletes the file. */
fun saveArchive(storage: Storage, notes: List<Note>) {
    if (notes.isEmpty()) storage.delete(ARCHIVE) else storage.write(ARCHIVE, notesToJsonl(notes))
}

// ---- per-species use scores: a pick count that fades with time (see UseScore.kt), so recent
//      picks rank above once-frequent-now-stale ones. Replaces the old recent.json + int uses.json. ----

fun loadUses(storage: Storage, now: Long = Clock.System.now().toEpochMilliseconds()): Map<String, UseEntry> =
    runCatching { storage.read("uses.json")?.let { parseUses(it, now) } }.getOrNull() ?: emptyMap()

fun saveUses(storage: Storage, uses: Map<String, UseEntry>) = storage.write("uses.json", usesJson(uses))

// ---- per-activity use counts, so your most-used activities rank to the top ----

private fun loadCounts(storage: Storage, file: String): Map<String, Int> =
    runCatching { storage.read(file)?.let(::parseCounts) }.getOrNull() ?: emptyMap()

private fun saveCounts(storage: Storage, file: String, counts: Map<String, Int>) = storage.write(file, countsJson(counts))

fun loadActUses(storage: Storage) = loadCounts(storage, "act_uses.json")
fun saveActUses(storage: Storage, uses: Map<String, Int>) = saveCounts(storage, "act_uses.json", uses)

// ---- co-observer name register (autocomplete) + "følget mitt" (the current party) ----

/** Pick counts per co-observer name, so names you enter often surface first in the picker. */
fun loadCoObsUses(storage: Storage) = loadCounts(storage, "coobs_uses.json")
fun saveCoObsUses(storage: Storage, uses: Map<String, Int>) = saveCounts(storage, "coobs_uses.json", uses)

/** Users picked from the co-observer search (see [AoUser]), by brukeralias. */
fun loadCoObsUsers(storage: Storage): Map<String, AoUser> =
    runCatching { storage.read("coobs_users.json")?.let(::parseCoObsUsers) }.getOrNull() ?: emptyMap()

fun saveCoObsUsers(storage: Storage, users: Collection<AoUser>) = storage.write("coobs_users.json", coObsUsersJson(users))

/** The current field party ("følget mitt"), persisted so it survives restarts - a trip lasts across
 *  app launches, and it can be set from the list header before there are any notes (#176). null when
 *  never written, which seeds it from the newest note instead (the pre-#176 derived behaviour). */
fun loadParty(storage: Storage): List<String>? = runCatching { storage.read("party.json")?.let(::parseParty) }.getOrNull()

fun saveParty(storage: Storage, party: List<String>) = storage.write("party.json", partyJson(party))

// ---- species-name language preference (#155): which language to show as primary + secondary ----

/** Load the saved languages, or [default] (the flavor's) on a fresh install - so out of the box the
 *  app matches the old hard-wired per-flavor behaviour. */
fun loadLangPrefs(storage: Storage, default: LangPrefs): LangPrefs =
    runCatching { storage.read("lang_prefs.json")?.let { parseLangPrefs(it, default) } }.getOrNull() ?: default

fun saveLangPrefs(storage: Storage, prefs: LangPrefs) = storage.write("lang_prefs.json", langPrefsJson(prefs))
