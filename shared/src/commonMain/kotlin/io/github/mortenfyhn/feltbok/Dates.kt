package io.github.mortenfyhn.feltbok

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** Month and weekday names for display, plus the day-header words. Spelled out per language rather
 *  than taken from the platform's locale tables: common Kotlin has none, and they disagree anyway
 *  (the JVM abbreviates June "juni" in Bokmål, Android "jun."). */
class DateNames(
    val months: List<String>, // abbreviated, no trailing dot, January first
    val weekdays: List<String>, // abbreviated and capitalised (they start a day header), Monday first
    val today: String,
    val yesterday: String,
)

private fun Long.local(zone: TimeZone): LocalDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone)

private fun two(n: Int) = n.toString().padStart(2, '0')

/** [ms] in a numeric pattern of yyyy, MM, dd, HH and mm - the export formats, e.g. "dd.MM.yyyy". */
fun formatDateTime(ms: Long, pattern: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = ms.local(zone)
    return pattern.replace("yyyy", t.year.toString()).replace("MM", two(t.monthNumber)).replace("dd", two(t.dayOfMonth))
        .replace("HH", two(t.hour)).replace("mm", two(t.minute))
}

fun shortTime(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String = formatDateTime(ms, "HH:mm", zone)

private fun dayMonth(d: LocalDate, names: DateNames) = "${d.dayOfMonth}. ${names.months[d.monthNumber - 1]}"

/** The editor's one-line time summary: a single point, or "from – to" for a range. When the
 *  time-of-day is unspecified only the date shows (no HH:mm), matching how it exports. */
fun displayTimeRange(
    start: Long,
    end: Long?,
    timeUnknown: Boolean,
    names: DateNames,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    fun fmt(ms: Long) = dayMonth(ms.local(zone).date, names) + if (timeUnknown) "" else " " + shortTime(ms, zone)
    return if (end == null) fmt(start) else "${fmt(start)} – ${fmt(end)}"
}

/** Friendlier date for the editor (export keeps the strict numeric format). */
fun displayDate(ms: Long, names: DateNames, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    ms.local(zone).date.let { "${dayMonth(it, names)} ${it.year}" }

// ---- day grouping (the notes list's per-day section headers) ----

/** A calendar day's notes for the list, with a header label relative to today. */
data class DayGroup(val label: String, val notes: List<Note>)

/** Label a day relative to [today]: "I dag", "I går", else an abbreviated date like "Søn 8. jun"
 *  (with year appended when it isn't [today]'s). */
private fun dayLabel(day: LocalDate, today: LocalDate, names: DateNames): String = when (day) {
    today -> names.today
    today.minus(1, DateTimeUnit.DAY) -> names.yesterday
    else -> buildString {
        append(names.weekdays[day.dayOfWeek.isoDayNumber - 1])
        append(" ")
        append(dayMonth(day, names))
        if (day.year != today.year) append(" ${day.year}")
    }
}

/** Group notes into one section per calendar day, newest day first and newest observation first
 *  within each day — ordered by observation time, not entry order, so a note dated in the past
 *  drops into its own day instead of jumping to the top. Pure ([today]/[zone] injected) for tests. */
fun groupNotesByDay(
    notes: List<Note>,
    names: DateNames,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.todayIn(zone),
): List<DayGroup> =
    notes.groupBy { it.time.local(zone).date }
        .entries.sortedByDescending { it.key }
        .map { (day, ns) -> DayGroup(dayLabel(day, today, names), ns.sortedByDescending { it.time }) }

/** The current time in epoch milliseconds. */
fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** This month, 1-12, in the device's time zone. */
fun currentMonth(): Int = Clock.System.todayIn(TimeZone.currentSystemDefault()).monthNumber
