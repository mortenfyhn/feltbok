package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MySitesTest {
    @Test
    fun sessionExpiresInIsReadFromTheClaims() {
        val claims = """[{"type":"sub","value":"x"},{"type":"bff:session_expires_in","value":2545695}]"""
        assertEquals(2545695L, parseSessionExpiresIn(claims))
        assertEquals(60L, parseSessionExpiresIn("""[{"type":"bff:session_expires_in","value":"60"}]"""))
        assertNull(parseSessionExpiresIn("""[{"type":"sub","value":"x"}]"""))
    }

    @Test
    fun totalPagesDefaultsToOne() {
        assertEquals(3, parseTotalPages("""{"data":[],"totalPages":3}"""))
        assertEquals(1, parseTotalPages("""{"data":[]}"""))
    }
}
