package com.appobs

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class Screen { LIST, SEARCH, DETAIL, LOCALITY }

/**
 * Single source of truth for the UI. Holds the day's notes (persisted), the live
 * GPS fix, and the draft being added/edited. The whole app is four screens driven
 * by [screen]; there's no nav library — overkill for this.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val tracker = LocationTracker(app)

    val localities: List<Locality> = loadLocalities(app)
    val species: List<Species> = loadSpecies(app)

    val notes = mutableStateListOf<Note>().apply { addAll(loadNotes(app)) }
    /** Recently chosen species (norsk), most-recent first — the quick list when search is empty. */
    val recent = mutableStateListOf<String>().apply { addAll(species.take(6).map { it.norsk }) }

    var screen by mutableStateOf(Screen.LIST); private set
    var showExport by mutableStateOf(false); private set
    // Paste links on the bare name; including coords mints custom localities
    // (paste behaves unlike the file-upload test). Default off, like iGoTerra.
    var exportCoords by mutableStateOf(false)
    var fix by mutableStateOf<GpsFix?>(null); private set

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
    val isEditing: Boolean get() = editingId != null

    init {
        viewModelScope.launch {
            tracker.fix.collect { f ->
                fix = f
                // Fill the locality once GPS settles, if adding and untouched.
                if (screen == Screen.DETAIL && editingId == null && dLoc == null) dLoc = nearest()
            }
        }
    }

    fun startLocationUpdates() = tracker.start()
    fun stopLocationUpdates() = tracker.stop()

    /** Official locality nearest the current fix, or null until GPS settles. */
    fun nearest(): Locality? =
        fix?.let { f -> localities.minByOrNull { haversine(f.lat, f.lon, it.lat, it.lon) } }

    fun distanceTo(loc: Locality): Double? =
        fix?.let { haversine(it.lat, it.lon, loc.lat, loc.lon) }

    // ---- navigation / actions ----
    fun startAdd() {
        editingId = null; changingSpecies = false
        dSpecies = ""; dLatin = ""; dCount = 1
        dAge = ""; dAct = ""; dSex = ""; dPub = ""; dPriv = ""
        dLoc = null; dTime = 0L
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
        if (!changingSpecies && !isEditing) {
            dTime = System.currentTimeMillis()  // stamp the entry time now
            dLoc = nearest()
        }
        changingSpecies = false
        screen = Screen.DETAIL
    }

    fun editNote(n: Note) {
        editingId = n.id; changingSpecies = false
        dSpecies = n.species; dLatin = n.latin; dCount = n.count
        dAge = n.age; dAct = n.activity; dSex = n.sex
        dPub = n.publicComment; dPriv = n.privateComment
        dTime = n.id
        dLoc = localities.firstOrNull { it.lokalitet == n.locName && it.lat == n.lat && it.lon == n.lon }
            ?: Locality("", n.locName, "", "", n.lat, n.lon)
        screen = Screen.DETAIL
    }

    fun openLocalityPicker() { screen = Screen.LOCALITY }
    fun pickLocality(loc: Locality) { dLoc = loc; screen = Screen.DETAIL }

    fun setCount(n: Int) { dCount = n.coerceAtLeast(1) }

    fun save() {
        val loc = dLoc ?: nearest()   // fall back to GPS-nearest if not picked yet
        val n = Note(
            id = if (isEditing) editingId!! else (dTime.takeIf { it > 0 } ?: System.currentTimeMillis()),
            species = dSpecies, latin = dLatin, count = dCount.coerceAtLeast(1),
            age = dAge, activity = dAct, sex = dSex,
            publicComment = dPub, privateComment = dPriv,
            locName = loc?.lokalitet ?: "", lat = loc?.lat ?: 0.0, lon = loc?.lon ?: 0.0,
        )
        if (isEditing) {
            val i = notes.indexOfFirst { it.id == n.id }
            if (i >= 0) notes[i] = n else notes.add(0, n)
        } else notes.add(0, n)
        persist()
        screen = Screen.LIST
    }

    fun delete() {
        editingId?.let { id -> notes.removeAll { it.id == id }; persist() }
        screen = Screen.LIST
    }

    fun openExport() { showExport = true }
    fun closeExport() { showExport = false }
    fun exportText(): String = exportTsv(notes, exportCoords)

    private fun persist() = saveNotes(ctx, notes)
}
