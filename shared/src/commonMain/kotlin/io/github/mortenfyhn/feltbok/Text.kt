package io.github.mortenfyhn.feltbok

import kotlin.math.abs
import kotlin.math.roundToLong

// Small text formatters common Kotlin lacks (String.format, Android's Uri.encode).

/** [v] with exactly [decimals] decimals and [separator] as the decimal mark, rounded half away from
 *  zero like String.format's "%.Nf". */
fun formatDecimal(v: Double, decimals: Int, separator: Char = '.'): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(v) * scale).roundToLong()
    val whole = (scaled / scale).toString()
    val sign = if (v < 0) "-" else "" // like String.format, even when it rounds to zero
    if (decimals == 0) return sign + whole
    return sign + whole + separator + (scaled % scale).toString().padStart(decimals, '0')
}

private const val HEX = "0123456789ABCDEF"

/** Percent-encodes [s] for a URI component like Android's Uri.encode: ASCII letters, digits and
 *  `_-!.~'()*` pass through, everything else becomes UTF-8 %XX. */
fun uriEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in "_-!.~'()*") {
            append(ch)
        } else {
            append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }
}
