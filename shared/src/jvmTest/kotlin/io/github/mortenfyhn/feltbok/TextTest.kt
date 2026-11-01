package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TextTest {
    @Test
    fun formatDecimalMatchesStringFormat() {
        // What the export and the distance label used String.format for.
        for (v in listOf(63.4305149, 10.39506, -0.0000004, -12.3456785, 0.0, 7.05, 1234.95)) {
            assertEquals(String.format(Locale.US, "%.6f", v), formatDecimal(v, 6))
            assertEquals(String.format(Locale.US, "%.1f", v), formatDecimal(v, 1))
        }
        assertEquals("63,430515", formatDecimal(63.4305149, 6, ','))
    }

    @Test
    fun uriEncodeMatchesAndroid() {
        assertEquals("Feltbok%20v1.3%0AGoogle%20Pixel", uriEncode("Feltbok v1.3\nGoogle Pixel"))
        assertEquals("%C3%A6%C3%B8%C3%A5%26%3D%2B%2F", uriEncode("æøå&=+/"))
        assertEquals("a_b-c!d.e~f'g(h)i*", uriEncode("a_b-c!d.e~f'g(h)i*"))
    }
}
