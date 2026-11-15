package io.github.mortenfyhn.feltbok

/** The batch-editable fields one edit changed, each paired with its new value. Comments and the
 *  uncertain flag are left out because batch edit can't set them, so repeating them shouldn't
 *  suggest it. */
fun batchableChanges(before: Note, after: Note): Set<Pair<String, Any?>> = buildSet {
    fun field(name: String, f: (Note) -> Any?) { if (f(before) != f(after)) add(name to f(after)) }
    field("species") { it.latin }
    field("count") { it.count }
    field("age") { it.age }
    field("sex") { it.sex }
    field("activity") { it.activity }
    field("locality") { Triple(it.locName, it.lat, it.lon) }
    field("time") { it.time to it.endTime }
    field("coObservers") { it.coObservers }
    field("hideUntil") { it.hideUntil }
}

/** One single-note edit: which note, and what it changed. */
data class NoteEdit(val id: Long, val changes: Set<Pair<String, Any?>>)

/** Whether [cur] repeats [prev] on another note: it sets a field to the same value. That's the
 *  moment to point out batch edit, which would have done both at once. */
fun repeatsEdit(prev: NoteEdit?, cur: NoteEdit): Boolean =
    prev != null && prev.id != cur.id && prev.changes.intersect(cur.changes).isNotEmpty()

/** Marker file: the batch-edit tip has been shown and dismissed, so it never shows again. */
const val BATCH_TIP_SEEN = "batch_tip_seen"
