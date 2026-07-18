package io.github.mortenfyhn.feltbok

/**
 * Pure, Android-free logic behind note selection + batch edit (#120), kept out of [MainViewModel]
 * so it can be unit-tested (see SelectionLogicTest) - this is the logic a regression would break
 * silently. The ViewModel is a thin caller: it owns the observable state and delegates the maths here.
 */

/** Which notes a day-header circle toggles: when the whole day is already marked it clears the day,
 *  otherwise it adds the day's still-unmarked notes (Material's tri-less "parent checkbox"). */
fun toggledDay(selected: Set<Long>, dayIds: List<Long>): Set<Long> =
    if (dayIds.isNotEmpty() && dayIds.all { it in selected }) selected - dayIds.toSet()
    else selected + dayIds

/** The inclusive run of ids swept between two rows in a long-press-drag, given their positions in
 *  the display-ordered id list. Order-independent (drag up or down); empty if either index is off-list. */
fun sweepRange(orderedIds: List<Long>, anchor: Int, cursor: Int): List<Long> =
    if (anchor !in orderedIds.indices || cursor !in orderedIds.indices) emptyList()
    else orderedIds.subList(minOf(anchor, cursor), maxOf(anchor, cursor) + 1)

/** A species pick travels as a pair - the two fields must move together or an edit would leave a
 *  note's common and scientific names mismatched. */
/** A batch species change: [name] is the exported registry name (Country.exportLang), [latin] the key. */
data class SpeciesPick(val name: String, val latin: String)

/** A time pick sets both endpoints together: [end] null makes it a single instant (Til = Fra). */
data class BatchTime(val start: Long, val end: Long?)

/**
 * One batch edit. Each non-null field is written to every targeted note; a null field is left as it
 * was on each note (so an edit touches only what you changed, never blanking the rest). A blank
 * string is a real value - it clears that field - so "leave alone" is null, not "".
 */
data class BatchChange(
    val species: SpeciesPick? = null,
    val count: Int? = null,
    val age: String? = null,
    val sex: String? = null,
    val activity: String? = null,
    val locality: Locality? = null,
    val time: BatchTime? = null,
) {
    /** Nothing to write - the editor was left with no field changed, so a save is a no-op. */
    val isNoOp: Boolean get() =
        species == null && count == null && age == null && sex == null &&
            activity == null && locality == null && time == null
}

/** Apply [change] to every note whose id is in [ids]; all other notes pass through untouched, and
 *  order is preserved. The locality mapping mirrors commitDraft() exactly, so a batch relocate is
 *  indistinguishable from re-picking the locality in each note's editor by hand. */
fun applyBatchEdit(notes: List<Note>, ids: Set<Long>, change: BatchChange): List<Note> =
    notes.map { n ->
        if (n.id !in ids) return@map n
        var m = n.copy(
            species = change.species?.name ?: n.species,
            latin = change.species?.latin ?: n.latin,
            count = change.count ?: n.count,
            age = change.age ?: n.age,
            sex = change.sex ?: n.sex,
            activity = change.activity ?: n.activity,
        )
        change.locality?.let { loc ->
            m = m.copy(
                locName = loc.lokalitet, locFull = "", lat = loc.lat, lon = loc.lon,
                newLoc = loc.newLoc, locRadius = if (loc.newLoc) loc.radius.toInt() else 0,
                kommune = loc.kommune,
            )
        }
        change.time?.let { m = m.copy(time = it.start, endTime = it.end) }
        m
    }

/** Preview of one field across the marked notes: the shared value when they agree, else the distinct
 *  non-blank values joined (the row truncates to whatever fits). Blank-only collapses to "". */
fun batchFieldPreview(values: List<String>): String {
    val distinct = values.distinct()
    return if (distinct.size <= 1) distinct.firstOrNull().orEmpty()
    else distinct.filter { it.isNotBlank() }.joinToString(", ")
}
