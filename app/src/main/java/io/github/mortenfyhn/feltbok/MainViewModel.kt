package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { LIST, SEARCH, DETAIL, LOCALITY, SYNC }

/**
 * Single source of truth for the UI. Holds the day's notes (persisted), the live
 * GPS fix, and the draft being added/edited. The whole app is four screens driven
 * by [screen]; there's no nav library - overkill for this.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val tracker = LocationTracker(app)

    // Parsing the ~11.5k bundled localities takes ~0.7 s, so load them off the main thread and
    // fill this observable list when ready - the app shows the notes list instantly meanwhile.
    // Nothing on the first screen needs localities; nearest()/the picker just see an empty list
    // until it populates (a beat later), then recompose.
    val localities = mutableStateListOf<Locality>()
    val species: List<Species> = loadSpecies(app)

    /** Species normalized (folded forms, token splits) once for the search scorer - never per keystroke. */
    private val prepared = prepare(species)

    /** Status code (Rødlista 2021 or Fremmedartslista 2023) by scientific name, for the badge. */
    private val statusByLatin: Map<String, String> =
        species.filter { it.status.isNotBlank() }.associate { it.latin to it.status }
    fun statusFor(latin: String): String = statusByLatin[latin] ?: ""

    val notes = mutableStateListOf<Note>().apply {
        importSeedNotes(app)   // dev-build only: import a pushed seed file, if any (see importSeedNotes)
        addAll(loadNotes(app))
    }

    /** True once the list is in selection mode. Kept separate from [selected] being non-empty so the
     *  mode stays put when you deselect the last mark - you leave it only via the ✕ / Back / an action
     *  (Jotta-style), not by emptying the set (which would be the Google Photos "auto-exit"). */
    var selectionMode by mutableStateOf(false); private set

    /** Notes marked for a bulk action (delete/export). A tap toggles a mark instead of opening the
     *  editor; selection mode is entered by long-pressing a note or a day header. */
    val selected = mutableStateListOf<Long>()
    fun toggleSelect(id: Long) { if (!selected.remove(id)) selected.add(id) }
    fun clearSelection() { selected.clear(); selectionMode = false }

    /** Long-press entry point: switch into selection mode and mark what was pressed. */
    fun startSelect(id: Long) { selectionMode = true; if (id !in selected) selected.add(id) }
    fun startSelectDay(ids: List<Long>) { selectionMode = true; toggleDay(ids) }

    /** Toggle a whole day group at once (the date header's circle): mark all of it, or - when it's
     *  already fully marked - clear it. Mirrors Material's "parent checkbox" for a section. */
    fun toggleDay(ids: List<Long>) {
        if (ids.all { it in selected }) selected.removeAll(ids) else selected.addAll(ids.filter { it !in selected })
    }
    fun deleteSelected() {
        setUndo(Undoable.Deleted(notes.filter { it.id in selected }))
        notes.removeAll { it.id in selected }
        clearSelection()
        persist()
    }

    // The pending undo offer (#122). [undoToken] bumps on each new action so the UI shows a fresh
    // snackbar; [undo] dispatches by kind. State + the "dismiss clears it" rule live in UndoOffer.
    private val undoOffer = UndoOffer()
    val undoToken: Int get() = undoOffer.token
    val undoable: Undoable? get() = undoOffer.current

    private fun setUndo(u: Undoable) = undoOffer.offer(u)

    fun undo() {
        when (val u = undoOffer.current) {
            is Undoable.Deleted -> { notes.addAll(u.notes); persist() }   // list sorts by time
            is Undoable.Discarded -> screen = Screen.DETAIL              // draft is still in the editor
            null -> {}
        }
        undoOffer.clear()
    }

    /** Drop the pending undo without acting - the snackbar timed out or was dismissed. */
    fun dismissUndo() = undoOffer.clear()

    /** Per-species use score (norsk): a pick count that fades with time (see [UseEntry]), so your
     *  recent picks rank above ones you logged a lot but long ago. Persisted. */
    private val uses = mutableStateMapOf<String, UseEntry>().apply { putAll(loadUses(app)) }

    /** Your decayed personal score for [norsk] as of [now] - the soft boost folded into rankings. */
    fun useScore(norsk: String, now: Long = System.currentTimeMillis()): Double =
        uses[norsk]?.let { decayedScore(it, now) } ?: 0.0

    /** Context-aware report frequency for ranking: what's reported in the current month and near the
     *  current GPS fix. Off-season/elsewhere/rare birds drop in rank but stay findable (tiers still
     *  match). The app blends this into the commonness weight handed to [rankSpecies]. */
    private val ctxFreq = ContextualFrequency(species, loadSpeciesMonths(app), loadSpeciesRegions(app))

    /** The blank-search quick list: your most-recently-picked species, newest first. If the bird isn't
     *  right at the top you'd start typing anyway, so a short recents list beats a ranked blend. When
     *  you have too few recents to fill it (a fresh install, early in an outing), it's padded with what
     *  the typed search shows for an empty query - the season + use-score blend - so it's never sparse. */
    fun blankQuickList(now: Long = System.currentTimeMillis()): List<Species> {
        val byNorsk = species.associateBy { it.norsk }
        val recent = uses.entries
            .sortedByDescending { it.value.lastTouched }
            .mapNotNull { byNorsk[it.key] }
        if (recent.size >= 20) return recent.take(20)
        val have = recent.mapTo(HashSet()) { it.norsk }
        val month = java.time.LocalDate.now().monthValue
        val filler = species
            .filter { it.norsk !in have }
            .sortedByDescending { blendedWeight(ctxFreq.weight(it, month, fix?.lat, fix?.lon), useScore(it.norsk, now)) }
        return (recent + filler).take(20)
    }

    /** Ranked matches for a typed query: [rankSpecies] (prefix/suffix/initialism/typo tiers + a
     *  commonness multiplier), with your regulars boosted. Ranking lives in Search.kt so it's
     *  unit-benchmarked. Month + location are snapshotted once per query into the weight lambda - not
     *  re-read for each of ~600 species, and not shared across overlapping searches - off the main thread. */
    suspend fun searchResults(query: String): List<Species> = withContext(Dispatchers.Default) {
        val month = java.time.LocalDate.now().monthValue
        val f = fix
        val now = System.currentTimeMillis()
        // Snapshot context + your decayed use score into one per-species likelihood (see blendedWeight)
        // - computed once per query here, off the main thread.
        val likelihood: (Species) -> Double = { s ->
            blendedWeight(ctxFreq.weight(s, month, f?.lat, f?.lon), useScore(s.norsk, now))
        }
        // distinct() collapses a species that matched on both its primary and alt name to one row,
        // keeping the higher-ranked occurrence (results are best-first).
        rankSpecies(query, prepared, likelihood).map { it.species }.distinct()
    }

    /** Per-activity use counts, so each user's most-used activities rise to the top. */
    private val actUses = mutableStateMapOf<String, Int>().apply { putAll(loadActUses(app)) }

    /** Aktivitet options with your most-used first, then the rest in the default order. */
    fun activityOptions(): List<String> {
        val (used, rest) = Country.activities.partition { (actUses[it] ?: 0) > 0 }
        return used.sortedByDescending { actUses[it] ?: 0 } + rest
    }

    var screen by mutableStateOf(Screen.LIST); private set
    var showExport by mutableStateOf(false); private set
    var fix by mutableStateOf<GpsFix?>(null); private set

    // Remembered locality-picker zoom, so reopening it keeps your last zoom level.
    var mapZoom = 16.0

    // ---- draft (current add/edit) ----
    // Observable so isEditing recomposes the editor when it changes without a screen switch -
    // e.g. copyAsNew turning an edit into a fresh observation in place (#110).
    private var editingId by mutableStateOf<Long?>(null)
    private var changingSpecies = false
    var dSpecies by mutableStateOf("")
    var dLatin by mutableStateOf("")
    var dCount by mutableStateOf(1)
    var dAge by mutableStateOf("")
    var dAct by mutableStateOf("")
    var dSex by mutableStateOf("")
    var dPub by mutableStateOf("")
    var dPriv by mutableStateOf("")
    var dLoc by mutableStateOf<Locality?>(null)
    var dTime by mutableStateOf(0L)
    var dEndTime by mutableStateOf<Long?>(null)   // observation end; null = single time point
    var dUncertain by mutableStateOf(false)
    val isEditing: Boolean get() = editingId != null

    /** Whether leaving the editor would actually lose work, so Back/✕ can skip the discard confirm
     *  when there's nothing to discard. A new observation is always unsaved work; an edit only
     *  counts if a field changed from the saved note. */
    fun draftHasChanges(): Boolean {
        val orig = editingId?.let { id -> notes.firstOrNull { it.id == id } } ?: return true
        val loc = dLoc
        return dSpecies != orig.species || dLatin != orig.latin || dCount != orig.count ||
            dAge != orig.age || dAct != orig.activity || dSex != orig.sex ||
            dPub != orig.publicComment || dPriv != orig.privateComment ||
            dTime != orig.time || dEndTime != orig.endTime || dUncertain != orig.uncertain ||
            (loc?.lokalitet ?: "") != orig.locName ||
            (loc?.lat ?: 0.0) != orig.lat || (loc?.lon ?: 0.0) != orig.lon
    }

    init {
        // Load localities off the main thread, then run the steps that depend on them.
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) { loadLocalities(ctx) }
            localities.addAll(loaded)
            onLocalitiesLoaded()
        }
        viewModelScope.launch {
            tracker.fix.collect { f ->
                fix = f
                // Fill the locality once GPS settles, if adding and untouched.
                if (screen == Screen.DETAIL && editingId == null && dLoc == null) dLoc = currentLocality()
            }
        }
    }

    /** Localities-dependent startup, run once they've finished loading. */
    private fun onLocalitiesLoaded() {
        // Re-add brand-new spots from saved notes, so a custom locality stays selectable
        // across restarts (until it's been uploaded and adjusted on the website).
        for (n in notes) if (n.newLoc && n.locName.isNotBlank())
            addNewLocality(Locality("", n.locName, "", n.kommune, n.lat, n.lon, 0,
                n.locRadius.toDouble(), public = false, newLoc = true))
        nearestFix = null   // invalidate the fix-keyed memo so nearest() rescans now that we have data
    }

    fun startLocationUpdates() = tracker.start()
    fun stopLocationUpdates() = tracker.stop()

    // nearest() is read on every recomposition (status strip, save button) but only changes
    // when the fix does, so memoize the last full-table scan against the fix it was computed for.
    private var nearestFix: GpsFix? = null
    private var nearestLoc: Locality? = null

    /** Official locality nearest the current fix, or null until GPS settles. */
    fun nearest(): Locality? {
        val f = fix ?: return null
        if (f !== nearestFix) {
            nearestFix = f
            nearestLoc = localities.minByOrNull { haversine(f.lat, f.lon, it.lat, it.lon) }
        }
        return nearestLoc
    }

    fun distanceTo(loc: Locality): Double? =
        fix?.let { haversine(it.lat, it.lon, loc.lat, loc.lon) }

    // The last locality you used + the fix where you used it, so a run of observations from one
    // spot "sticks" to your chosen locality instead of snapping to whatever you're closest to.
    private var lastUsedLoc: Locality? = null
    private var lastUsedFix: GpsFix? = null

    /** The "current" locality: keep the last one used/picked while you're still within ~50 m of
     *  where you used it; re-snap to the GPS-nearest only after a genuine move (good fix). Shown on
     *  the list and used as the default for a new observation. */
    fun currentLocality(): Locality? {
        val last = lastUsedLoc ?: return nearest()
        val from = lastUsedFix; val now = fix
        val movedAway = from != null && now != null && now.accuracyM <= 35f &&
            haversine(now.lat, now.lon, from.lat, from.lon) >= 50.0
        return if (movedAway) nearest() else last
    }

    // ---- navigation / actions ----
    fun startAdd() {
        resetDraft()
        screen = Screen.SEARCH
    }

    /** Clear the observation draft. Called when starting a new one and after saving, so a stale
     *  dTime (the id source) can't leak into the next note via a path that skips re-stamping it
     *  - e.g. createNewLocality/changeSpecies - and mint two notes with the same id. */
    private fun resetDraft() {
        editingId = null; changingSpecies = false; fromCopy = false
        dSpecies = ""; dLatin = ""; dCount = 1
        dAge = ""; dAct = ""; dSex = ""; dPub = ""; dPriv = ""; dUncertain = false
        dLoc = null; dTime = 0L; dEndTime = null
    }

    fun changeSpecies() { changingSpecies = true; screen = Screen.SEARCH }
    fun cancelSearch() { screen = if (dSpecies.isNotEmpty() || isEditing) Screen.DETAIL else Screen.LIST }
    fun backToDetail() { screen = Screen.DETAIL }
    fun cancel() { screen = Screen.LIST }

    /** Leave the editor, throwing away the in-progress observation/edits - no confirm, but offer an
     *  undo (#122). The draft is left intact (as [cancel] does), so undo just reopens the editor. */
    fun discardDraft() { setUndo(Undoable.Discarded(isEditing)); screen = Screen.LIST }

    fun pickSpecies(s: Species) {
        dSpecies = s.norsk; dLatin = s.latin
        val now = System.currentTimeMillis()
        uses[s.norsk] = bumpUse(uses[s.norsk], now)
        saveUses(ctx, uses)
        if (!changingSpecies && !isEditing) {
            dTime = now  // stamp the entry time now
            dLoc = currentLocality()
        }
        changingSpecies = false
        screen = Screen.DETAIL
    }

    fun editNote(n: Note) {
        editingId = n.id; changingSpecies = false; fromCopy = false
        dSpecies = n.species; dLatin = n.latin; dCount = n.count
        dAge = n.age; dAct = n.activity; dSex = n.sex
        dPub = n.publicComment; dPriv = n.privateComment
        dTime = n.time; dEndTime = n.endTime; dUncertain = n.uncertain
        dLoc = localities.firstOrNull { it.lokalitet == n.locName && it.lat == n.lat && it.lon == n.lon }
            ?: Locality("", n.locName, "", "", n.lat, n.lon, 0, 0.0)
        screen = Screen.DETAIL
    }

    /** Bumped on each copyAsNew so the editor can play a slide-in: copying stays on the DETAIL
     *  screen, so without this cue the near-identical form makes it unclear a new copy was made. */
    var copyToken by mutableStateOf(0); private set

    // True while the draft is a copy of an existing observation (not editing it, but carrying its
    // location). Lets the picker focus on that inherited locality like editing does, instead of
    // re-centring on the GPS fix as it would for a genuinely new observation.
    var fromCopy by mutableStateOf(false); private set

    /** Commit the current draft, then keep every field as-is (species, count, location, time, …)
     *  while dropping the editing link, so the next [save] mints a new note instead of overwriting.
     *  Used to enter a run of similar observations: each Kopier saves what you've entered and hands
     *  you a prefilled copy to tweak. Committing first means editing a note and copying no longer
     *  silently loses the edits to the original (#130). The comments are observation-specific, so
     *  clear them rather than carry them into the copy (#136). */
    fun copyAsNew() {
        commitDraft(); editingId = null; changingSpecies = false; copyToken++; fromCopy = true
        dPub = ""; dPriv = ""
    }

    // True while the picker was opened from the list to set the "current" locality, rather than
    // from the observation draft. Routes selection/back to the list instead of the detail screen.
    var pickingCurrent by mutableStateOf(false); private set

    fun openLocalityPicker() { pickingCurrent = false; screen = Screen.LOCALITY }
    fun openCurrentLocalityPicker() { pickingCurrent = true; screen = Screen.LOCALITY }

    /** The locality the picker should open focused on and highlight. */
    val pickerFocus: Locality? get() = if (pickingCurrent) currentLocality() else dLoc

    fun pickLocality(loc: Locality) {
        if (pickingCurrent) {
            lastUsedLoc = loc; lastUsedFix = fix   // stick it, same as after saving an observation
            pickingCurrent = false
            screen = Screen.LIST
        } else {
            dLoc = loc; screen = Screen.DETAIL
        }
    }

    /** Leave the picker without choosing, back to wherever it was opened from. */
    fun leaveLocalityPicker() {
        if (pickingCurrent) { pickingCurrent = false; screen = Screen.LIST } else backToDetail()
    }

    // ---- "Synk mine lokaliteter" (pull the user's own privates from Artsobservasjoner) ----
    fun openSync() { screen = Screen.SYNC }
    fun closeSync() { screen = Screen.LIST }

    /** Replace the user's own (mine) localities with a freshly synced set, persist, and re-pick
     *  the nearest. Public localities and in-progress new spots are left untouched. Returns what
     *  changed versus the previous set, for the sync confirmation. */
    fun applyMySites(sites: List<Locality>): SyncDiff {
        val diff = diffMySites(localities.filter { it.mine }, sites)
        localities.removeAll { it.mine }
        localities.addAll(sites)
        saveMyLocalities(ctx, sites)
        nearestFix = null   // invalidate the fix-keyed memo so nearest() rescans
        return diff
    }

    /** Place a brand-new spot (panned-to map centre + chosen radius + name). It exports
     *  with coordinates, which mints the locality on Artsobservasjoner. The name is optional:
     *  blank gets a placeholder you can rename later on the website. */
    fun createNewLocality(lat: Double, lon: Double, radiusM: Int, name: String) {
        val finalName = name.trim().ifBlank { "Ny lokalitet" }
        // Stamp the surrounding kommune now (localities are loaded - you just placed it on the map),
        // so its observations export under the right kommune without a later lookup.
        val loc = Locality("", finalName, "", nearestKommune(lat, lon, localities), lat, lon, 0,
            radiusM.toDouble(), public = false, newLoc = true)
        addNewLocality(loc)        // make it immediately selectable for further observations
        dLoc = loc
        screen = Screen.DETAIL
    }

    /** Add a brand-new spot to the pickable list (deduped by name + position). */
    private fun addNewLocality(loc: Locality) {
        if (localities.none { it.newLoc && it.lokalitet == loc.lokalitet && it.lat == loc.lat && it.lon == loc.lon })
            localities.add(loc)
    }

    fun setCount(n: Int) { dCount = if (n < 1) UNKNOWN_COUNT else n }

    fun save() {
        commitDraft()
        resetDraft()
        screen = Screen.LIST
    }

    /** Persist the current draft as a note (insert when adding, replace when editing) without
     *  touching navigation or the draft, so both [save] and [copyAsNew] share one store path. */
    private fun commitDraft() {
        val loc = dLoc ?: nearest()   // fall back to GPS-nearest if not picked yet
        val n = Note(
            // id is the stable key; bump past any same-millisecond collision so it stays unique.
            id = if (isEditing) editingId!! else {
                var id = dTime.takeIf { it > 0 } ?: System.currentTimeMillis()
                while (notes.any { it.id == id }) id++
                id
            },
            time = dTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            endTime = dEndTime,
            species = dSpecies, latin = dLatin, count = dCount,
            age = dAge, activity = dAct, sex = dSex,
            publicComment = dPub, privateComment = dPriv,
            locName = loc?.lokalitet ?: "", locFull = "",
            lat = loc?.lat ?: 0.0, lon = loc?.lon ?: 0.0,
            newLoc = loc?.newLoc == true, locRadius = if (loc?.newLoc == true) loc.radius.toInt() else 0,
            uncertain = dUncertain, kommune = loc?.kommune ?: "",
        )
        if (isEditing) {
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(0, n)
        } else notes.add(0, n)
        if (loc != null && !isEditing) { lastUsedLoc = loc; lastUsedFix = fix }   // stick it for the next obs
        if (dAct.isNotBlank()) {
            actUses[dAct] = (actUses[dAct] ?: 0) + 1
            saveActUses(ctx, actUses)
        }
        persist()
    }

    fun delete() {
        editingId?.let { id ->
            setUndo(Undoable.Deleted(notes.filter { it.id == id }))
            notes.removeAll { it.id == id }
            persist()
        }
        screen = Screen.LIST
    }

    // Export is all-at-once: copy every note and paste in one go. A per-kommune flow was dropped on
    // purpose - scoping the form to a kommune only matters to disambiguate a name shared across
    // kommuner, and a single-kommune batch can already be scoped via the import hint. Running
    // without it will show how often that conflict actually bites (fylke scope is a site-side fallback).
    // Which notes the open export covers: null = every note (the top-strip button); a set of ids =
    // just those marked (the selection-mode Eksporter, #120). The scope is a snapshot taken at open,
    // so it survives a later selection change - and the marks stay put, so backing out of export
    // lands you back in selection mode where you left it.
    private var exportScope by mutableStateOf<List<Long>?>(null)
    fun openExport() { exportScope = null; showExport = true }
    fun exportSelected() { exportScope = selected.toList(); showExport = true }
    fun closeExport() { showExport = false }

    /** The notes the current export covers - the marked ones when exporting a selection, else all. */
    fun exportNotes(): List<Note> = exportScope?.let { ids -> notes.filter { it.id in ids } } ?: notes

    /** True when the open export is scoped to a selection (vs the whole list) - drives the clear
     *  step's wording so it never claims to wipe "alle observasjoner" when it only clears a subset. */
    fun exportIsSelection(): Boolean = exportScope != null

    /** Kommuner present across the exported notes, for the import hint: a single (non-blank) one can
     *  be named on the form for safer matching. */
    fun exportKommuner(): List<String> = groupNotesByKommune(exportNotes(), localities).map { it.kommune }

    /** Clear the exported notes - after they've been imported and published. Only the export's scope
     *  (a selection, or all), so a subset export never deletes notes it didn't cover. Undoable like
     *  the other deletes (#122), so a mis-tap recovers. */
    fun clearExported() {
        val gone = exportNotes()
        setUndo(Undoable.Deleted(gone))
        val ids = gone.map { it.id }.toSet()
        notes.removeAll { it.id in ids }
        clearSelection()  // the exported notes are gone; leave selection mode too
        persist()
    }

    private fun persist() {
        saveNotes(ctx, notes)
        backupExport(ctx, notes)   // mirror to Downloads so a UI crash can't trap field data (#85)
    }
}
