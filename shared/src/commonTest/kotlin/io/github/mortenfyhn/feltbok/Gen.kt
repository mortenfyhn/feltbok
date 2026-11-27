package io.github.mortenfyhn.feltbok

import kotlin.random.Random

/** Random test input from a fixed seed (#201), so a failing case can be replayed: the failure
 *  message names the seed. */
class Gen(val seed: Int) {
    val r = Random(seed)

    // Characters that break text formats: quotes, separators, line breaks, a NUL, letters outside
    // ASCII and an emoji (two UTF-16 units).
    private val odd = listOf("\"", "\\", ",", ";", "\t", "\n", "\r\n", "\u0000", "æøå", "ÆØÅ", "é", "🐦", "{", "]", " ", "")

    fun string(max: Int = 30): String = buildString {
        repeat(r.nextInt(max + 1)) {
            append(if (r.nextInt(4) == 0) odd.random(r) else ('a' + r.nextInt(26)))
        }
    }

    fun <T> oneOf(vararg xs: T): T = xs.random(r)

    fun <T> orNull(x: T): T? = if (r.nextBoolean()) x else null

    private fun double() = oneOf(0.0, -0.0, 63.4305, -180.0, 90.0, 1e-9, r.nextDouble(-90.0, 90.0))

    // Any time from 1970 to 2100.
    private fun time() = oneOf(0L, 1L, r.nextLong(0, 4_102_444_800_000))

    fun note(id: Long = r.nextLong()): Note = Note(
        id = id,
        time = time(),
        endTime = orNull(time()),
        timeUnknown = r.nextBoolean(),
        species = string(),
        latin = string(),
        count = oneOf(UNKNOWN_COUNT, 0, 1, r.nextInt(10_000), Int.MAX_VALUE),
        age = oneOf("", "Adult", "1K", string()),
        activity = oneOf("", "Rastende", string()),
        sex = oneOf("", "Hann", "I par", string()),
        publicComment = string(300),
        privateComment = string(300),
        locName = string(),
        locFull = string(),
        lat = double(),
        lon = double(),
        newLoc = r.nextBoolean(),
        locRadius = r.nextInt(10_000),
        uncertain = r.nextBoolean(),
        coObservers = List(r.nextInt(4)) { string(15) },
        kommune = string().ifBlank { "" }, // the app saves a blank kommune or site id as absent
        locId = oneOf("", "352401", string().ifBlank { "" }),
        hideUntil = orNull(time()),
        sightingId = orNull(r.nextLong()),
    )

    fun notes(max: Int = 20): List<Note> = withUniqueIds(List(r.nextInt(max + 1)) { note() })

    /** [text] damaged one of several ways: cut short, a run of bytes changed, a run deleted or
     *  doubled, or blanked. */
    fun damage(text: String): String {
        if (text.isEmpty()) return string()
        val i = r.nextInt(text.length)
        val j = (i + 1 + r.nextInt(20)).coerceAtMost(text.length)
        return when (r.nextInt(5)) {
            0 -> text.take(i)
            1 -> text.substring(0, i) + string(j - i) + text.substring(j)
            2 -> text.removeRange(i, j)
            3 -> text.substring(0, j) + text.substring(i)
            else -> oneOf("", " ", "null", "{}", "[]", "<html>", "\u0000\u0000")
        }
    }
}

/** Runs [body] with [n] seeds, naming the seed when one fails. */
fun forSeeds(n: Int = 200, body: Gen.() -> Unit) {
    for (seed in 0 until n) {
        try {
            Gen(seed).body()
        } catch (e: Throwable) {
            throw AssertionError("Failed with seed $seed: $e", e)
        }
    }
}
