package com.feltbok

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the undo snackbar would reverse (#122): a delete (re-add the notes) or a draft discard
 *  (reopen the editor - the draft is left intact on discard, so nothing to restore). */
sealed interface Undoable {
    data class Deleted(val notes: List<Note>) : Undoable
    data class Discarded(val wasEdit: Boolean) : Undoable
}

/** The single pending undo offer. [token] bumps each time a new offer replaces the last, so the UI
 *  shows a fresh snackbar; [current] is what the undo would act on. Clearing it (the snackbar timed
 *  out, was dismissed, or the undo was taken) MUST drop [current] - else a later recomposition (e.g.
 *  after a rotation, which keeps the ViewModel) revives a snackbar for an offer that's already gone.
 *  Android-free so that rule is unit-testable (see UndoOfferTest). */
class UndoOffer {
    var current by mutableStateOf<Undoable?>(null); private set
    var token by mutableStateOf(0); private set

    fun offer(u: Undoable) { current = u; token++ }
    fun clear() { current = null }
}
