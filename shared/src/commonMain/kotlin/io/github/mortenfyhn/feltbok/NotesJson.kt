package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// The text formats of notes.json and the archive. The files themselves are read and written by
// the platform.

/**
 * Two observations entered in the same millisecond share an `id` (it's the creation time).
 * `id` is the list's stable key and the handle for edit/delete/select, so a collision both
 * crashes LazyColumn and makes those actions ambiguous. Heal by nudging duplicates forward
 * a millisecond until unique - `id` is internal only (not shown or exported), so this is safe.
 */
fun withUniqueIds(notes: List<Note>): List<Note> {
    val seen = HashSet<Long>()
    return notes.map { n ->
        var id = n.id
        while (!seen.add(id)) id++
        if (id == n.id) n else n.copy(id = id)
    }
}

/** The per-field JSON mapping for a [Note], kept as pure functions so a round-trip test can catch
 *  a field that's saved but not loaded, or vice versa - a silent data-loss bug on the next launch.
 *  endTime is the one optional field: omitted when null, restored as null. */
internal fun noteToJson(n: Note): JsonObject = buildJsonObject {
    put("id", n.id); put("time", n.time); put("species", n.species); put("latin", n.latin)
    n.endTime?.let { put("endTime", it) }
    if (n.timeUnknown) put("timeUnknown", true)
    put("count", n.count); put("age", n.age); put("activity", n.activity)
    put("sex", n.sex); put("publicComment", n.publicComment)
    put("privateComment", n.privateComment)
    put("locName", n.locName); put("locFull", n.locFull)
    put("lat", n.lat); put("lon", n.lon)
    put("newLoc", n.newLoc); put("locRadius", n.locRadius); put("uncertain", n.uncertain)
    if (n.coObservers.isNotEmpty()) put("coObservers", JsonArray(n.coObservers.map(::JsonPrimitive)))
    if (n.kommune.isNotBlank()) put("kommune", n.kommune)
    n.hideUntil?.let { put("hideUntil", it) }
}

internal fun noteFromJson(o: JsonObject): Note = Note(
    id = o.getLong("id"),
    time = o.optLong("time", o.getLong("id")),
    endTime = if (o.has("endTime")) o.getLong("endTime") else null,
    timeUnknown = o.optBoolean("timeUnknown", false),
    species = o.getString("species"),
    latin = o.optString("latin"),
    count = o.getInt("count"),
    age = o.optString("age"),
    activity = o.optString("activity"),
    sex = o.optString("sex"),
    publicComment = o.optString("publicComment"),
    privateComment = o.optString("privateComment"),
    locName = o.optString("locName"),
    locFull = o.optString("locFull"),
    lat = o.getDouble("lat"),
    lon = o.getDouble("lon"),
    newLoc = o.optBoolean("newLoc"),
    locRadius = o.optInt("locRadius"),
    uncertain = o.optBoolean("uncertain"),
    coObservers = o.optArray("coObservers")?.map { it.jsonPrimitive.content } ?: emptyList(),
    kommune = o.optString("kommune"),
    hideUntil = if (o.has("hideUntil")) o.getLong("hideUntil") else null,
)

/** notes.json's content: a JSON array of [noteToJson] objects. Throws on a malformed file. */
fun parseNotes(text: String): List<Note> = withUniqueIds(parseJson(text).jsonArray.map { noteFromJson(it.jsonObject) })

fun notesJson(notes: List<Note>): String = JsonArray(notes.map(::noteToJson)).toString()

// ---- archive (#153): archived (ex-deleted) observations, one JSON object per line ----
// JSON Lines rather than a JSON array so archiving can APPEND without parsing the existing
// archive; only the archive screen and a restore read the whole file.

fun notesToJsonl(notes: List<Note>): String =
    notes.joinToString("") { noteToJson(it).toString() + "\n" }

/** A malformed line (e.g. a torn append from a crash) is skipped, not fatal - the archive is a
 *  safety net, so one bad line must never take the rest of it down. */
fun notesFromJsonl(text: String): List<Note> =
    text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> runCatching { noteFromJson(parseJson(line).jsonObject) }.getOrNull() }
        .toList()
