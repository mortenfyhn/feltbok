package com.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UndoOfferTest {
    private val anOffer = Undoable.Deleted(emptyList())

    /** Regression (#122): a dismissed/timed-out offer must leave nothing behind. Otherwise a later
     *  recomposition - e.g. rotating the phone, which keeps the ViewModel - revives the snackbar
     *  for an offer that's already gone. */
    @Test
    fun clearLeavesNothingToShow() {
        val undo = UndoOffer()
        undo.offer(anOffer)
        assertNotNull(undo.current)
        undo.clear()
        assertNull(undo.current)
    }

    /** Each new offer bumps the token so the UI shows a fresh snackbar instead of reusing the last. */
    @Test
    fun eachOfferBumpsToken() {
        val undo = UndoOffer()
        val start = undo.token
        undo.offer(anOffer)
        undo.offer(anOffer)
        assertEquals(start + 2, undo.token)
    }
}
