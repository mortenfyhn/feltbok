package com.appobs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

enum class AppState { LOADING_MODEL, READY, RECORDING, TRANSCRIBING }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val state = MutableStateFlow(AppState.LOADING_MODEL)
    val transcription = MutableStateFlow("")
    val downloadProgress = MutableStateFlow(0f)

    private var whisperContext: WhisperContext? = null
    private val recorder = AudioRecorder()
    private val modelFile = File(app.filesDir, "ggml-small-q5_1.bin")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (!modelFile.exists()) {
                downloadModel()
            }
            whisperContext = WhisperContext.createFromFile(modelFile.absolutePath)
            state.value = AppState.READY
        }
    }

    private fun downloadModel() {
        val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin")
        val tmpFile = File(modelFile.parent, "${modelFile.name}.tmp")
        url.openStream().use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                val totalBytes = 181_000_000L
                var bytesRead = 0L
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    bytesRead += len
                    downloadProgress.value = (bytesRead.toFloat() / totalBytes).coerceAtMost(0.99f)
                }
            }
        }
        tmpFile.renameTo(modelFile)
        downloadProgress.value = 1f
    }

    fun startRecording() {
        if (state.value != AppState.READY) return
        recorder.start()
        state.value = AppState.RECORDING
        transcription.value = ""
    }

    fun stopRecordingAndTranscribe() {
        if (state.value != AppState.RECORDING) return
        val audioData = recorder.stop()
        state.value = AppState.TRANSCRIBING
        viewModelScope.launch(Dispatchers.IO) {
            val result = whisperContext?.transcribe(audioData) ?: "Feil: modell ikke lastet"
            transcription.value = result.trim()
            state.value = AppState.READY
        }
    }

    override fun onCleared() {
        viewModelScope.launch { whisperContext?.release() }
    }
}
