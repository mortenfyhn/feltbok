package com.appobs

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "AppObs"

enum class AppState { READY, RECORDING }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableStateFlow(AppState.READY)
    val count = MutableStateFlow(0)
    val lastSaved = MutableStateFlow<String?>(null)

    private val recorder = AudioRecorder()
    private val tracker = LocationTracker(app)
    val fix: StateFlow<GpsFix?> = tracker.fix

    private val dir = File(app.getExternalFilesDir(null), "recordings").apply { mkdirs() }
    private val tmpFile = File(dir, "current.m4a")

    // Single thread so start/stop of the recorder are serialized and can never
    // race, while the UI state flips instantly on the main thread.
    private val recorderDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        count.value = recordings().size
    }

    // GPS runs only while the screen is visible; the Activity drives this from
    // its lifecycle so the receiver stays warm without draining battery in the
    // background.
    fun startLocationUpdates() = tracker.start()
    fun stopLocationUpdates() = tracker.stop()

    fun startRecording() {
        if (state.value != AppState.READY) return
        state.value = AppState.RECORDING   // instant visual feedback
        lastSaved.value = null
        viewModelScope.launch(recorderDispatcher) {
            try {
                recorder.start(tmpFile)
                Log.i(TAG, "Recording started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                lastSaved.value = "Feil ved opptak: ${e.message}"
                state.value = AppState.READY
            }
        }
    }

    fun stopRecording() {
        if (state.value != AppState.RECORDING) return
        state.value = AppState.READY       // instant visual feedback
        val current = fix.value            // capture position at the moment of stop
        viewModelScope.launch(recorderDispatcher) {
            val ok = recorder.stop()
            if (!ok) {
                tmpFile.delete()
                lastSaved.value = "For kort opptak"
                return@launch
            }
            val name = buildName(current)
            if (!tmpFile.renameTo(File(dir, name))) {
                Log.e(TAG, "Failed to rename recording")
                lastSaved.value = "Feil ved lagring"
                return@launch
            }
            count.value = recordings().size
            lastSaved.value = when {
                current == null -> "Lagret (uten GPS!)"
                current.accuracyM.isNaN() -> "Lagret (GPS-nøyaktighet ukjent)"
                else -> "Lagret ±${current.accuracyM.toInt()} m"
            }
            Log.i(TAG, "Saved $name")
        }
    }

    /** Discard the most recent note — for an accidental tap or a changed mind. */
    fun deleteLast() {
        if (state.value == AppState.RECORDING) return
        val last = recordings().lastOrNull() ?: return
        last.delete()
        count.value = recordings().size
        lastSaved.value = "Slettet siste"
    }

    /** The saved notes, for the share action. Excludes the in-progress temp file. */
    fun recordings(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".m4a") && f.name != tmpFile.name }
            ?.sortedBy { it.name }
            ?: emptyList()

    // Encode all field metadata in the filename so there is nothing else to
    // sync: the laptop script parses time + position + accuracy straight back
    // out of this. NA marks a value we couldn't capture, so it gets flagged
    // for manual review later rather than silently dropped.
    private fun buildName(fix: GpsFix?): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        val lat = fix?.let { "%.6f".format(Locale.US, it.lat) } ?: "NA"
        val lon = fix?.let { "%.6f".format(Locale.US, it.lon) } ?: "NA"
        val acc = fix?.takeIf { !it.accuracyM.isNaN() }?.let { it.accuracyM.toInt().toString() } ?: "NA"
        return "${ts}_lat${lat}_lon${lon}_acc${acc}.m4a"
    }
}
