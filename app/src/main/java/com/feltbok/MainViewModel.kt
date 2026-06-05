package com.feltbok

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
    /** Norwegian names pre-folded for search, parallel to [species] (folded once, not per keystroke). */
    val foldedNorsk: List<String> = species.map { fold(it.norsk) }
    /** Rødlista 2021 category by scientific name, for the conservation-status badge. */
    private val statusByLatin: Map<String, String> =
        species.filter { it.status.isNotBlank() }.associate { it.latin to it.status }
    fun redStatus(latin: String): String = statusByLatin[latin] ?: ""

    val notes = mutableStateListOf<Note>().apply { addAll(loadNotes(app)) }
    /** Recently chosen species (norsk), most-recent first - the quick list when search is empty.
     *  Persisted, so it survives restarts; seeds with the most common species first run. */
    val recent = mutableStateListOf<String>().apply {
        val saved = loadRecent(app)
        addAll(if (saved.isNotEmpty()) saved else species.take(6).map { it.norsk })
    }
    /** How many times each species (norsk) has been picked, so your own regulars
     *  rank to the top of the quick list and of typed results. Persisted. */
    private val uses = mutableStateMapOf<String, Int>().apply { putAll(loadUses(app)) }
    fun useCount(norsk: String): Int = uses[norsk] ?: 0

    /** Per-activity use counts, so each user's most-used activities rise to the top. */
    private val actUses = mutableStateMapOf<String, Int>().apply { putAll(loadActUses(app)) }
    /** Aktivitet options with your most-used first, then the rest in the default order. */
    fun activityOptions(): List<String> {
        val (used, rest) = Options.activities.partition { (actUses[it] ?: 0) > 0 }
        return used.sortedByDescending { actUses[it] ?: 0 } + rest
    }

    /** Last non-empty comments entered, offered as "som forrige" on a new note -
     *  handy when the same remark applies to a run of observations. */
    var lastPub by mutableStateOf(""); private set
    var lastPriv by mutableStateOf(""); private set

    var screen by mutableStateOf(Screen.LIST); private set
    var showExport by mutableStateOf(false); private set
    var fix by mutableStateOf<GpsFix?>(null); private set

    // Remembered locality-picker zoom, so reopening it keeps your last zoom level.
    var mapZoom = 16.0

    // ---- draft (current add/edit) ----
    private var editingId: Long? = null
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

    init {
        // Seed "som forrige" from the most recent note that carried a comment (no localities needed).
        notes.firstOrNull { it.publicComment.isNotBlank() }?.let { lastPub = it.publicComment }
        notes.firstOrNull { it.privateComment.isNotBlank() }?.let { lastPriv = it.privateComment }
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
        // Backfill qualified locality names on notes saved before `locFull` existed,
        // so re-exporting an earlier day links instead of failing on the bare name.
        var migrated = false
        for (i in notes.indices) {
            val n = notes[i]
            if (n.locFull.isBlank() && n.locName.isNotBlank()) {
                val loc = localities.firstOrNull { it.lokalitet == n.locName && it.lat == n.lat && it.lon == n.lon }
                    ?: localities.filter { it.lokalitet == n.locName }   // same name across kommuner:
                        .minByOrNull { haversine(it.lat, it.lon, n.lat, n.lon) }  // pick the nearest, not the first
                if (loc != null) { notes[i] = n.copy(locFull = loc.fullname); migrated = true }
            }
        }
        if (migrated) saveNotes(ctx, notes)
        // Re-add brand-new spots from saved notes, so a custom locality stays selectable
        // across restarts (until it's been uploaded and adjusted on the website).
        for (n in notes) if (n.newLoc && n.locName.isNotBlank())
            addNewLocality(Locality("", n.locName, "", "", n.lat, n.lon, n.locName, 0,
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

    /** Distance from the current fix to where a note was made, or null if unknown. */
    fun distanceToNote(n: Note): Double? =
        if (n.lat == 0.0 && n.lon == 0.0) null
        else fix?.let { haversine(it.lat, it.lon, n.lat, n.lon) }

    // ---- navigation / actions ----
    fun startAdd() {
        editingId = null; changingSpecies = false
        dSpecies = ""; dLatin = ""; dCount = 1
        dAge = ""; dAct = ""; dSex = ""; dPub = ""; dPriv = ""; dUncertain = false
        dLoc = null; dTime = 0L; dEndTime = null
        screen = Screen.SEARCH
    }

    fun changeSpecies() { changingSpecies = true; screen = Screen.SEARCH }
    fun cancelSearch() { screen = if (dSpecies.isNotEmpty() || isEditing) Screen.DETAIL else Screen.LIST }
    fun backToDetail() { screen = Screen.DETAIL }
    fun cancel() { screen = Screen.LIST }

    fun pickSpecies(s: Species) {
        dSpecies = s.norsk; dLatin = s.latin
        recent.remove(s.norsk); recent.add(0, s.norsk)
        while (recent.size > 8) recent.removeAt(recent.size - 1)
        saveRecent(ctx, recent)
        uses[s.norsk] = (uses[s.norsk] ?: 0) + 1
        saveUses(ctx, uses)
        if (!changingSpecies && !isEditing) {
            dTime = System.currentTimeMillis()  // stamp the entry time now
            dLoc = currentLocality()
        }
        changingSpecies = false
        screen = Screen.DETAIL
    }

    fun editNote(n: Note) {
        editingId = n.id; changingSpecies = false
        dSpecies = n.species; dLatin = n.latin; dCount = n.count
        dAge = n.age; dAct = n.activity; dSex = n.sex
        dPub = n.publicComment; dPriv = n.privateComment
        dTime = n.time; dEndTime = n.endTime; dUncertain = n.uncertain
        dLoc = localities.firstOrNull { it.lokalitet == n.locName && it.lat == n.lat && it.lon == n.lon }
            ?: Locality("", n.locName, "", "", n.lat, n.lon, n.locFull, 0, 0.0)
        screen = Screen.DETAIL
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
     *  the nearest. Public localities and in-progress new spots are left untouched. */
    fun applyMySites(sites: List<Locality>) {
        localities.removeAll { it.mine }
        localities.addAll(sites)
        saveMyLocalities(ctx, sites)
        nearestFix = null   // invalidate the fix-keyed memo so nearest() rescans
    }

    /** Place a brand-new spot (panned-to map centre + chosen radius + name). It exports
     *  with coordinates, which mints the locality on Artsobservasjoner. The name is optional:
     *  blank gets a placeholder you can rename later on the website. */
    fun createNewLocality(lat: Double, lon: Double, radiusM: Int, name: String) {
        val finalName = name.trim().ifBlank { "Ny lokalitet" }
        val loc = Locality("", finalName, "", "", lat, lon, finalName, 0, radiusM.toDouble(),
            public = false, newLoc = true)
        addNewLocality(loc)        // make it immediately selectable for further observations
        dLoc = loc
        screen = Screen.DETAIL
    }

    /** Add a brand-new spot to the pickable list (deduped by name + position). */
    private fun addNewLocality(loc: Locality) {
        if (localities.none { it.newLoc && it.lokalitet == loc.lokalitet && it.lat == loc.lat && it.lon == loc.lon })
            localities.add(loc)
    }

    fun setCount(n: Int) { dCount = n.coerceAtLeast(1) }

    fun save() {
        val loc = dLoc ?: nearest()   // fall back to GPS-nearest if not picked yet
        val n = Note(
            id = if (isEditing) editingId!! else (dTime.takeIf { it > 0 } ?: System.currentTimeMillis()),
            time = dTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            endTime = dEndTime,
            species = dSpecies, latin = dLatin, count = dCount.coerceAtLeast(1),
            age = dAge, activity = dAct, sex = dSex,
            publicComment = dPub, privateComment = dPriv,
            locName = loc?.lokalitet ?: "", locFull = loc?.fullname ?: "",
            lat = loc?.lat ?: 0.0, lon = loc?.lon ?: 0.0,
            newLoc = loc?.newLoc == true, locRadius = if (loc?.newLoc == true) loc.radius.toInt() else 0,
            uncertain = dUncertain,
        )
        if (isEditing) {
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(0, n)
        } else notes.add(0, n)
        if (loc != null && !isEditing) { lastUsedLoc = loc; lastUsedFix = fix }   // stick it for the next obs
        if (dPub.isNotBlank()) lastPub = dPub
        if (dPriv.isNotBlank()) lastPriv = dPriv
        if (dAct.isNotBlank()) {
            actUses[dAct] = (actUses[dAct] ?: 0) + 1
            saveActUses(ctx, actUses)
        }
        persist()
        screen = Screen.LIST
    }

    fun delete() {
        editingId?.let { id -> notes.removeAll { it.id == id }; persist() }
        screen = Screen.LIST
    }

    fun openExport() { showExport = true }
    fun closeExport() { showExport = false }
    fun exportText(): String = exportTsv(notes)

    /** Clear the day's notes - after they've been imported and published. */
    fun clearAll() { notes.clear(); persist() }

    private fun persist() = saveNotes(ctx, notes)
}
