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

enum class Screen { LIST, SEARCH, DETAIL, LOCALITY, COOBS, SYNC, SETTINGS, ARCHIVE }

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
    val species: List<Species> = loadSpecies(app) + loadUbestemt(app)

    /** Species normalized (folded forms, token splits) for the search scorer - never per keystroke.
     *  Cached per active-language set ([LangPrefs.searchLangs]) and rebuilt only when that changes,
     *  so toggling the search language costs one re-prepare, not one per keystroke. */
    private var searchIndexCache: Pair<Set<Lang>, List<PreparedSpecies>>? = null
    private fun searchIndex(): List<PreparedSpecies> {
        val langs = langPrefs.searchLangs
        searchIndexCache?.let { if (it.first == langs) return it.second }
        return prepare(species, langs).also { searchIndexCache = langs to it }
    }

    /** Status code (Rødlista 2021 or Fremmedartslista 2023) by scientific name, for the badge. */
    private val statusByLatin: Map<String, String> =
        species.filter { it.status.isNotBlank() }.associate { it.latin to it.status }
    fun statusFor(latin: String): String = statusByLatin[latin] ?: ""

    // ---- species-name language preference (#155) ----
    private val speciesByLatin: Map<String, Species> = species.associateBy { it.latin }

    /** The chosen display languages (primary + optional secondary); observable so changing them on
     *  the Settings screen relabels the species list and note rows without a restart. */
    var langPrefs by mutableStateOf(loadLangPrefs(app)); private set

    fun updateLangPrefs(prefs: LangPrefs) {
        langPrefs = prefs
        saveLangPrefs(ctx, prefs)
    }

    /** Swedish names are shown with an initial capital ("Svartvit flugsnappare"); Norwegian stays
     *  all-lower-case and Latin keeps its own capitalised genus, so only SVENSK is transformed (#155).
     *  Display-only: the stored data (and the export name) stay lower-case. */
    private fun cap(name: String, lang: Lang) =
        if (lang == Lang.SVENSK) name.replaceFirstChar { it.uppercaseChar() } else name

    /** A species' name in [lang], falling back to Latin when a source lacks that name (e.g. IOC has
     *  no Swedish for a Norwegian-only vagrant) - Latin is the universal common denominator, so a
     *  primary name never shows blank. */
    private fun Species.display(lang: Lang): String {
        val raw = name(lang)
        return if (raw.isBlank()) latin else cap(raw, lang)
    }

    fun primaryName(s: Species): String = s.display(langPrefs.primary)

    /** The secondary name to show under the primary, or null when it's blank (a source lacks it) or
     *  would just repeat the primary (e.g. same language, or a missing name fell back to Latin). The
     *  repeat test is on the raw names so a case-only difference (Grågås/grågås) still collapses. */
    fun secondaryName(s: Species): String? {
        val sec = s.name(langPrefs.secondary)
        val pri = s.name(langPrefs.primary).ifBlank { s.latin }
        return if (sec.isBlank() || sec.equals(pri, ignoreCase = true)) null else cap(sec, langPrefs.secondary)
    }

    /** The primary display name for a species identified by [latin], resolved so it honours the
     *  current language choice; [fallback] (the stored name) is used if the latin isn't loaded. */
    fun nameForLatin(latin: String, fallback: String): String =
        speciesByLatin[latin]?.let { primaryName(it) } ?: cap(fallback, Country.exportLang)

    /** The primary display name for a saved note (see [nameForLatin]). */
    fun noteName(n: Note): String = nameForLatin(n.latin, n.species)

    /** The secondary display name for the species identified by [latin] (see [secondaryName]), or
     *  null when there's no secondary to show or the species isn't loaded. Used by the editor's
     *  picked-species row so it shows the chosen primary + secondary, like the search results. */
    fun secondaryNameForLatin(latin: String): String? = speciesByLatin[latin]?.let { secondaryName(it) }

    val notes = mutableStateListOf<Note>().apply {
        importSeedNotes(app)   // dev-build only: import a pushed seed file, if any (see importSeedNotes)
        addAll(loadNotes(app))
    }

    /** True once the list is in selection mode. Kept separate from [selected] being non-empty so the
     *  mode stays put when you deselect the last mark - you leave it only via the ✕ / Back / an action
     *  (Jotta-style), not by emptying the set (which would be the Google Photos "auto-exit"). */
    var selectionMode by mutableStateOf(false); private set

    /** True while the DETAIL screen is editing the whole selection at once (#120), rather than one
     *  note. The editor reuses the same draft; [batchBaseline] records the seeded values so save
     *  applies only the fields you actually changed. See [startBatchEdit] / [commitBatchEdit]. */
    var batchEditing by mutableStateOf(false); private set

    /** Notes marked for a bulk action (delete/export). A tap toggles a mark instead of opening the
     *  editor; selection mode is entered by long-pressing a note or a day header. */
    val selected = mutableStateListOf<Long>()
    fun toggleSelect(id: Long) { if (!selected.remove(id)) selected.add(id) }
    fun clearSelection() { selected.clear(); selectionMode = false }

    /** Long-press entry point on a day header: switch into selection mode and mark the whole day. */
    fun startSelectDay(ids: List<Long>) { selectionMode = true; toggleDay(ids) }

    /** Enter selection mode on one note. The archive taps straight into this: an archived note can't
     *  be opened, so marking is all a tap can mean there, and it saves discovering the long-press. */
    fun startSelect(id: Long) { selectionMode = true; toggleSelect(id) }

    /** Replace the whole marked set at once - the long-press-drag range selector paints a fresh set
     *  (a base plus the swept range) on every drag move, so shrinking the drag un-marks again. */
    fun setSelection(ids: Collection<Long>) {
        selectionMode = true
        selected.clear()
        selected.addAll(ids)
    }

    /** Toggle a whole day group at once (the date header's circle). Set-maths in [toggledDay]. */
    fun toggleDay(ids: List<Long>) = setSelection(toggledDay(selected.toSet(), ids))

    fun deleteSelected() {
        archive(notes.filter { it.id in selected })
        clearSelection()
    }

    // ---- archive (#153): "Slett" moves notes to the archive file instead of destroying them; the
    // archive screen restores them. True deletion is deliberately absent - clearing the app's data
    // is the escape hatch if someone really wants everything gone.

    /** Archived notes for the ARCHIVE screen, loaded fresh each time it opens (the archive can be
     *  big, so it isn't parsed at startup - see loadArchive). */
    val archived = mutableStateListOf<Note>()

    /** The shared "delete" path: append [gone] to the archive, drop them from the live list, and
     *  offer the usual undo. Appending immediately (not on snackbar expiry) means an app kill can't
     *  lose them; the undo therefore also takes them back OUT of the archive (see [undo]). */
    private fun archive(gone: List<Note>) {
        if (gone.isEmpty()) return
        appendToArchive(ctx, gone)
        setUndo(Undoable.Deleted(gone))
        val ids = gone.map { it.id }.toSet()
        notes.removeAll { it.id in ids }
        persist()
    }

    fun openArchive() {
        clearSelection()
        archived.clear()
        archived.addAll(loadArchive(ctx))
        screen = Screen.ARCHIVE
    }

    fun closeArchive() {
        clearSelection()
        screen = Screen.LIST
    }

    /** Move the marked archived notes back to the live list (they re-sort into their day groups). */
    fun restoreSelected() {
        val sel = archived.filter { it.id in selected }
        if (sel.isEmpty()) return
        archived.removeAll { it.id in selected }
        saveArchive(ctx, archived.toList())
        notes.addAll(sel)
        clearSelection()
        persist()
    }

    // ---- Batch edit (#120): apply fields to every marked note at once. Undoable (restores the prior
    // notes) and keeps the selection, so several fields can be set in a row. The transform itself is
    // pure ([applyBatchEdit]) and unit-tested; this just snapshots for undo, writes back, and persists.
    private fun batchApply(change: BatchChange) {
        val ids = selected.toSet()
        if (ids.isEmpty() || change.isNoOp) return
        setUndo(Undoable.Edited(notes.filter { it.id in ids }))
        val updated = applyBatchEdit(notes, ids, change)
        for (i in notes.indices) notes[i] = updated[i]   // 1:1, order preserved (no field reorders the list)
        persist()
    }

    /** Field previews for the batch editor: the shared value across the marks, or the mix. */
    fun batchPreview(selector: (Note) -> String): String =
        batchFieldPreview(notes.filter { it.id in selected }.map(selector))

    // The pending undo offer (#122). [undoToken] bumps on each new action so the UI shows a fresh
    // snackbar; [undo] dispatches by kind. State + the "dismiss clears it" rule live in UndoOffer.
    private val undoOffer = UndoOffer()
    val undoToken: Int get() = undoOffer.token
    val undoable: Undoable? get() = undoOffer.current

    private fun setUndo(u: Undoable) = undoOffer.offer(u)

    fun undo() {
        when (val u = undoOffer.current) {
            is Undoable.Deleted -> {   // list sorts by time
                notes.addAll(u.notes)
                // They were archived at once (see archive()), so take them back out - a note must
                // never exist in both lists.
                val ids = u.notes.map { it.id }.toSet()
                saveArchive(ctx, loadArchive(ctx).filterNot { it.id in ids })
                persist()
            }
            is Undoable.Discarded -> screen = Screen.DETAIL              // draft is still in the editor
            is Undoable.Edited -> { u.before.forEach { b -> notes.indexOfFirst { it.id == b.id }.takeIf { it >= 0 }?.let { notes[it] = b } }; persist() }
            null -> {}
        }
        undoOffer.clear()
    }

    /** Drop the pending undo without acting - the snackbar timed out or was dismissed. */
    fun dismissUndo() = undoOffer.clear()

    /** Per-species use score, keyed by scientific name: a pick count that fades with time (see
     *  [UseEntry]), so your recent picks rank above ones you logged a lot but long ago. Persisted.
     *  Keyed by latin (not the display name) so it survives a name-language change (#155); scores
     *  from the pre-#155 file (keyed by the then-displayed name) are migrated to latin on load. */
    private val uses = mutableStateMapOf<String, UseEntry>().apply {
        val byName = species.associateBy { it.norsk } + species.associateBy { it.svensk }
        loadUses(app).forEach { (key, entry) ->
            val latin = if (species.any { it.latin == key }) key else byName[key]?.latin
            if (latin != null) merge(latin, entry) { a, b -> if (a.lastTouched >= b.lastTouched) a else b }
        }
    }

    /** Your decayed personal score for [latin] as of [now] - the soft boost folded into rankings. */
    fun useScore(latin: String, now: Long = System.currentTimeMillis()): Double =
        uses[latin]?.let { decayedScore(it, now) } ?: 0.0

    /** Context-aware report frequency for ranking: what's reported in the current month and near the
     *  current GPS fix. Off-season/elsewhere/rare birds drop in rank but stay findable (tiers still
     *  match). The app blends this into the commonness weight handed to [rankSpecies]. */
    private val ctxFreq = ContextualFrequency(species, loadSpeciesMonths(app), loadSpeciesRegions(app))

    /** The blank-search quick list: your most-recently-picked species, newest first. If the bird isn't
     *  right at the top you'd start typing anyway, so a short recents list beats a ranked blend. When
     *  you have too few recents to fill it (a fresh install, early in an outing), it's padded with what
     *  the typed search shows for an empty query - the season + use-score blend - so it's never sparse. */
    fun blankQuickList(now: Long = System.currentTimeMillis()): List<Species> {
        val byLatin = species.associateBy { it.latin }
        val recent = uses.entries
            .sortedByDescending { it.value.lastTouched }
            .mapNotNull { byLatin[it.key] }
        if (recent.size >= 20) return recent.take(20)
        val have = recent.mapTo(HashSet()) { it.latin }
        val month = java.time.LocalDate.now().monthValue
        val filler = species
            .filter { it.latin !in have }
            .sortedByDescending { blendedWeight(ctxFreq.weight(it, month, fix?.lat, fix?.lon), useScore(it.latin, now)) }
        return (recent + filler).take(20)
    }

    /** Ranked matches for a typed query: [rankSpecies] (prefix/suffix/initialism/typo tiers + a
     *  commonness multiplier), with your regulars boosted. Ranking lives in Search.kt so it's
     *  unit-benchmarked. Month + location are snapshotted once per query into the weight lambda - not
     *  re-read for each of ~600 species, and not shared across overlapping searches - off the main thread. */
    suspend fun searchResults(query: String): List<Species> {
        val index = searchIndex() // reads langPrefs on the caller (main) context before going off-thread
        return withContext(Dispatchers.Default) {
            val month = java.time.LocalDate.now().monthValue
            val f = fix
            val now = System.currentTimeMillis()
            // Snapshot context + your decayed use score into one per-species likelihood (see
            // blendedWeight) - computed once per query here, off the main thread.
            val likelihood: (Species) -> Double = { s ->
                blendedWeight(ctxFreq.weight(s, month, f?.lat, f?.lon), useScore(s.latin, now))
            }
            // distinct() collapses a species that matched on several of its names to one row,
            // keeping the higher-ranked occurrence (results are best-first).
            rankSpecies(query, index, likelihood).map { it.species }.distinct()
        }
    }

    /** Per-activity use counts, so each user's most-used activities rise to the top. */
    private val actUses = mutableStateMapOf<String, Int>().apply { putAll(loadActUses(app)) }

    // ---- co-observers (#128): a name register for autocomplete, plus the sticky "følget mitt" party.
    /** Pick counts per co-observer name, so names you enter often surface first in the picker. */
    private val coObsUses = mutableStateMapOf<String, Int>().apply { putAll(loadCoObsUses(app)) }

    /** The current field party ("følget mitt"), pre-filled onto every new observation: simply the
     *  newest note's co-observers. Derived, not stored - whoever your latest obs credits *is* the
     *  party, so fixing that note (alone or in a batch) fixes the default for the next one, and
     *  editing older notes can't hijack it (#128). */
    private fun party(): List<String> = notes.maxByOrNull { it.time }?.coObservers ?: emptyList()

    /** Known co-observer names for the picker (pure ranking in [coObserverOptions]). */
    fun coObserverOptions(): List<String> = coObserverOptions(coObsUses, dCoObs)

    /** Toggle a name on/off the draft's co-observers. */
    fun toggleCoObs(name: String) { if (!dCoObs.remove(name)) dCoObs.add(name) }

    /** Add a free-text name to the draft (deduped, trimmed); no-op on blank. */
    fun addCoObs(name: String) {
        val n = name.trim()
        if (n.isNotBlank() && n !in dCoObs) dCoObs.add(n)
    }

    /** Clear the draft's co-observers ("tøm følget"); the party syncs to this on save. */
    fun clearCoObs() = dCoObs.clear()

    /** Forget a name entirely - drop it from the autocomplete register (so a mistyped or one-off
     *  name doesn't linger), and from the draft if it's there. Past notes keep their own copies,
     *  so this only prunes what you'll be *offered* going forward. */
    fun forgetCoObs(name: String) {
        coObsUses.remove(name)
        saveCoObsUses(ctx, coObsUses)
        dCoObs.remove(name)
    }

    /** Aktivitet options with your most-used first, then the rest in the default order. */
    fun activityOptions(): List<String> {
        val (used, rest) = Country.activities.partition { (actUses[it] ?: 0) > 0 }
        return used.sortedByDescending { actUses[it] ?: 0 } + rest
    }

    var screen by mutableStateOf(Screen.LIST); private set
    var showExport by mutableStateOf(false); private set
    var fix by mutableStateOf<GpsFix?>(null); private set

    // Id of a just-added note the list should scroll to reveal. A fresh obs starts a new day-group
    // above the current scroll position, so the list stays anchored on the old top and the new note
    // lands off-screen unless we scroll up to it. Consumed (cleared) once shown, so returning from an
    // edit doesn't re-scroll to it.
    var scrollToNoteId by mutableStateOf<Long?>(null); private set
    fun clearScrollTarget() { scrollToNoteId = null }

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
    var dTimeUnknown by mutableStateOf(false)     // time-of-day left unspecified (date still known)
    var dUncertain by mutableStateOf(false)
    val dCoObs = mutableStateListOf<String>()      // co-observers on the draft (#128)
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
            dTime != orig.time || dEndTime != orig.endTime || dTimeUnknown != orig.timeUnknown || dUncertain != orig.uncertain ||
            dCoObs.toList() != orig.coObservers ||
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
                // Fill the locality once GPS settles, if adding and untouched. NOT in batch edit:
                // there editingId is also null and dLoc may be null (mixed localities), but a batch
                // draft must stay put until the user picks - else it'd silently relocate every note.
                if (screen == Screen.DETAIL && editingId == null && dLoc == null && !batchEditing) dLoc = currentLocality()
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

    /** Default locality for the current fix, or null until GPS settles. If you're inside one or more
     *  localities' footprints, the *smallest* containing one wins (the most specific - matching how a
     *  tap resolves nesting, #126); otherwise the one whose footprint *edge* is nearest. So a big
     *  locality you're inside beats a small one you're outside, yet a small locality nested inside a
     *  big one still wins when you're inside both (#155). */
    fun nearest(): Locality? {
        val f = fix ?: return null
        if (f !== nearestFix) {
            nearestFix = f
            val inside = localities.filter { localityContains(it, f.lat, f.lon) }
            nearestLoc = if (inside.isNotEmpty()) inside.minByOrNull { footprintArea(it) }
            else localities.minByOrNull { distanceToFootprint(it, f.lat, f.lon) }
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
        dLoc = null; dTime = 0L; dEndTime = null; dTimeUnknown = false
        dCoObs.clear(); dCoObs.addAll(party())   // a new obs inherits the current party (#128)
    }

    fun changeSpecies() { changingSpecies = true; screen = Screen.SEARCH }
    fun cancelSearch() { screen = if (dSpecies.isNotEmpty() || isEditing) Screen.DETAIL else Screen.LIST }
    fun backToDetail() { screen = Screen.DETAIL }
    fun cancel() { screen = Screen.LIST }

    /** Leave the editor, throwing away the in-progress observation/edits - no confirm, but offer an
     *  undo (#122). The draft is left intact (as [cancel] does), so undo just reopens the editor. */
    fun discardDraft() { setUndo(Undoable.Discarded(isEditing)); screen = Screen.LIST }

    fun pickSpecies(s: Species) {
        // dSpecies holds the exported Artnamn/Artsnavn: always the registry's language, whatever the
        // user displays (#155). dLatin is the stable key used for display resolution + use scores.
        dSpecies = s.name(Country.exportLang); dLatin = s.latin
        val now = System.currentTimeMillis()
        uses[s.latin] = bumpUse(uses[s.latin], now)
        saveUses(ctx, uses)
        if (!changingSpecies && !isEditing && !batchEditing) {
            dTime = now  // stamp the entry time now
            dLoc = currentLocality()
        }
        changingSpecies = false
        screen = Screen.DETAIL
    }

    /** Commit a free-typed species name not in the checklist (#144). The typed text becomes the
     *  exported Artsnavn verbatim; latin is left blank (no registry entry), which the display
     *  fallbacks (nameForLatin) and export already tolerate. No use-score bump - there's no stable
     *  latin key to attribute it to, and it won't reappear in recents. */
    fun pickArbitrarySpecies(name: String) {
        dSpecies = name.trim(); dLatin = ""
        if (!changingSpecies && !isEditing && !batchEditing) {
            dTime = System.currentTimeMillis()
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
        dTime = n.time; dEndTime = n.endTime; dTimeUnknown = n.timeUnknown; dUncertain = n.uncertain
        dCoObs.clear(); dCoObs.addAll(n.coObservers)
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
            // Both a single-note edit and a batch edit (#120) live on DETAIL and edit the draft's dLoc.
            dLoc = loc; screen = Screen.DETAIL
        }
    }

    /** Leave the picker without choosing, back to wherever it was opened from. */
    fun leaveLocalityPicker() {
        if (pickingCurrent) { pickingCurrent = false; screen = Screen.LIST } else backToDetail()
    }

    // ---- co-observer picker (#128) ----
    fun openCoObs() { screen = Screen.COOBS }
    fun closeCoObs() { screen = Screen.DETAIL }

    // ---- settings (#155: species-name languages) ----
    fun openSettings() { screen = Screen.SETTINGS }
    fun closeSettings() { screen = Screen.LIST }

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
        if (batchEditing) { commitBatchEdit(); return }
        commitDraft()
        resetDraft()
        screen = Screen.LIST
    }

    // ---- Batch edit via the editor (#120) ----
    // Seeded values to diff the draft against on save, so only changed fields are written. A field
    // the selection agrees on is seeded to that shared value; a field they differ on is seeded blank
    // (the editor then shows a preview of the mix). locKey is the shared locality's identity, or null.
    private data class BatchBaseline(
        val species: String, val latin: String, val count: Int,
        val age: String, val sex: String, val activity: String,
        val locKey: String?, val time: Long, val endTime: Long?, val coObs: List<String>,
    )
    private var batchBaseline: BatchBaseline? = null
    private fun Locality.key() = "$lokalitet|$lat|$lon"
    private fun Note.locKey() = "$locName|$lat|$lon"

    /** Enter the editor to change the whole selection at once: seed the draft with shared values
     *  (blank where the notes differ), remember the seed, and open DETAIL in batch mode. */
    fun startBatchEdit() {
        val sel = notes.filter { it.id in selected }
        if (sel.isEmpty()) return
        // One note marked: there's nothing to batch - open the ordinary "Endre observasjon" editor.
        if (sel.size == 1) { editNote(sel.first()); return }
        fun <T> shared(f: (Note) -> T): T? = sel.map(f).distinct().singleOrNull()
        editingId = null; changingSpecies = false; fromCopy = false
        val latin = shared { it.latin }
        dSpecies = if (latin != null) sel.first().species else ""
        dLatin = latin ?: ""
        dCount = shared { it.count } ?: UNKNOWN_COUNT
        dAge = shared { it.age } ?: ""
        dSex = shared { it.sex } ?: ""
        dAct = shared { it.activity } ?: ""
        dPub = ""; dPriv = ""; dUncertain = false
        dCoObs.clear(); dCoObs.addAll(shared { it.coObservers } ?: emptyList())
        val locKey = shared { it.locKey() }
        dLoc = if (locKey != null) sel.first().let { n ->
            localities.firstOrNull { it.lokalitet == n.locName && it.lat == n.lat && it.lon == n.lon }
                ?: Locality("", n.locName, "", n.kommune, n.lat, n.lon, 0, 0.0)
        } else null
        dTime = shared { it.time } ?: 0L
        dEndTime = shared { it.endTime }
        dTimeUnknown = false   // batch edit doesn't touch the no-time flag; keep the checkbox off

        batchBaseline = BatchBaseline(dSpecies, dLatin, dCount, dAge, dSex, dAct, locKey, dTime, dEndTime, dCoObs.toList())
        batchEditing = true
        screen = Screen.DETAIL
    }

    /** Apply only the fields the user actually changed (draft vs the seeded baseline) to every marked
     *  note, then return to the list still in selection mode (so you can edit another field). */
    private fun commitBatchEdit() {
        val b = batchBaseline
        val change = BatchChange(
            species = if (dLatin.isNotBlank() && (dLatin != b?.latin || dSpecies != b.species)) SpeciesPick(dSpecies, dLatin) else null,
            count = if (dCount != b?.count) dCount else null,
            age = if (dAge != b?.age) dAge else null,
            sex = if (dSex != b?.sex) dSex else null,
            activity = if (dAct != b?.activity) dAct else null,
            locality = dLoc?.takeIf { it.key() != b?.locKey },
            time = if (dTime > 0 && (dTime != b?.time || dEndTime != b.endTime)) BatchTime(dTime, dEndTime) else null,
            coObservers = dCoObs.toList().takeIf { it != b?.coObs },
        )
        batchApply(change)
        // Bump the name register like a single-note save would, so a name typed here feeds the
        // autocomplete next time. The party needs no handling: it's derived from the newest note,
        // so a batch that covers it re-points the default automatically.
        change.coObservers?.takeIf { it.isNotEmpty() }?.let { names ->
            names.forEach { coObsUses[it] = (coObsUses[it] ?: 0) + 1 }
            saveCoObsUses(ctx, coObsUses)
        }
        cancelBatchEdit()   // leaves batch mode + resets the draft; the marks stay
    }

    /** Leave batch edit without applying (the ✕ / Back), back to the list with the marks intact. */
    fun cancelBatchEdit() {
        batchEditing = false; batchBaseline = null
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
            timeUnknown = dTimeUnknown,
            species = dSpecies, latin = dLatin, count = dCount,
            age = dAge, activity = dAct, sex = dSex,
            publicComment = dPub, privateComment = dPriv,
            locName = loc?.lokalitet ?: "", locFull = "",
            lat = loc?.lat ?: 0.0, lon = loc?.lon ?: 0.0,
            newLoc = loc?.newLoc == true, locRadius = if (loc?.newLoc == true) loc.radius.toInt() else 0,
            uncertain = dUncertain, coObservers = dCoObs.toList(), kommune = loc?.kommune ?: "",
        )
        if (isEditing) {
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) {
                // Moving an edit to another day lands it in a different day-group - maybe a new one
                // at the top (e.g. today) that's off-screen - so scroll to reveal it, as for a new obs.
                if (exportDate(notes[i].time) != exportDate(n.time)) scrollToNoteId = n.id
                notes[i] = n
            } else notes.add(0, n)
        } else { notes.add(0, n); scrollToNoteId = n.id }
        if (loc != null && !isEditing) { lastUsedLoc = loc; lastUsedFix = fix }   // stick it for the next obs
        if (dAct.isNotBlank()) {
            actUses[dAct] = (actUses[dAct] ?: 0) + 1
            saveActUses(ctx, actUses)
        }
        // Co-observers: bump the name register (for autocomplete). The party is derived from the
        // newest note, so the save itself is what makes it stick - nothing to sync.
        if (dCoObs.isNotEmpty()) {
            dCoObs.forEach { coObsUses[it] = (coObsUses[it] ?: 0) + 1 }
            saveCoObsUses(ctx, coObsUses)
        }
        persist()
    }

    fun delete() {
        editingId?.let { id -> archive(notes.filter { it.id == id }) }
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
        archive(exportNotes())
        clearSelection()  // the exported notes are gone; leave selection mode too
    }

    private fun persist() {
        saveNotes(ctx, notes)
        backupExport(ctx, notes)   // mirror to Downloads so a UI crash can't trap field data (#85)
    }
}
