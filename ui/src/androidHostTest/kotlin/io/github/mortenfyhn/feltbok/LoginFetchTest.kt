package io.github.mortenfyhn.feltbok

import io.github.mortenfyhn.feltbok.FakePlatform.Companion.loggedInClaims
import io.github.mortenfyhn.feltbok.FakePlatform.Companion.site
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The requests behind logging in: the cookie on each one, the login check and ByUser's paging. */
class LoginFetchTest {
    private val p = FakePlatform()

    @Test
    fun siteFetchSendsTheSessionCookieOnlyWhenThereIsOne() = runBlocking {
        p.answers["ByBoundingBox"] = { "[${site(1, "A", false)}]" }
        fetchSites(p, 59.9, 10.7, 59.91, 10.72)
        assertNull(p.requests.last().second["Cookie"])
        p.cookie = "__Host-bff=abc"
        assertEquals(1, fetchSites(p, 59.9, 10.7, 59.91, 10.72)!!.size)
        assertEquals("__Host-bff=abc", p.requests.last().second["Cookie"])
    }

    @Test
    fun sessionEndIsNullWithoutACookieOrWhenTheCheckFails() = runBlocking {
        p.answers["/bff/user"] = { null } // 401
        assertNull(fetchSessionEnd(p))
        assertTrue("no cookie, so no request", p.requests.isEmpty())
        p.cookie = "c=1"
        assertNull(fetchSessionEnd(p))
        p.answers["/bff/user"] = { loggedInClaims(100) }
        val end = fetchSessionEnd(p)!!
        assertTrue(end - nowMs() in 90_000L..100_000L)
        assertEquals("c=1", p.requests.last().second["Cookie"])
    }

    @Test
    fun mySitesPagesThroughEveryPageOrFailsWhole() = runBlocking {
        assertNull("no cookie", fetchMySites(p))
        p.cookie = "c=1"
        p.answers["pageNumber=1"] = { """{"data":[${site(10, "Mine 1", true)}],"totalPages":2}""" }
        p.answers["pageNumber=2"] = { """{"data":[${site(11, "Mine 2", true)}],"totalPages":2}""" }
        val sites = fetchMySites(p)!!
        assertEquals(listOf("10", "11"), sites.map { it.id })
        assertTrue(sites.all { it.mine && !it.public })
        assertTrue(p.requestsTo("ByUser").all { it.second["Cookie"] == "c=1" })
        p.answers["pageNumber=2"] = { null }
        assertNull("a failed page fails the whole fetch, so a partial set never replaces yours", fetchMySites(p))
    }

    @Test
    fun updateToastNamesWhatChanged() {
        fun d(total: Int, changed: Int, first: Boolean = false) = SyncDiff(total, changed, first)
        assertEquals("Allerede oppdatert", Strings.Sync.updated(0, d(99, 0)))
        assertEquals("Henta 99 private lokaliteter", Strings.Sync.updated(0, d(99, 99, first = true)))
        assertEquals("Henta 1 allmenn lokalitet", Strings.Sync.updated(1, d(99, 0)))
        assertEquals("Henta 3 allmenne lokaliteter", Strings.Sync.updated(3, d(99, 0)))
        assertEquals("Henta 1 endra privat lokalitet", Strings.Sync.updated(0, d(99, 1)))
        assertEquals(
            "Henta 2 allmenne lokaliteter og 3 endra private lokaliteter",
            Strings.Sync.updated(2, d(99, 3)),
        )
    }
}
