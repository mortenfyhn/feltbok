package com.appobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import java.util.concurrent.Executors

class WhisperContext private constructor(private var ptr: Long) {
    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    suspend fun transcribe(data: FloatArray): String = scope.async {
        val threads = Runtime.getRuntime().availableProcessors()
        WhisperLib.fullTranscribe(ptr, threads, data)
        buildString {
            for (i in 0 until WhisperLib.getTextSegmentCount(ptr)) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }
    }.await()

    suspend fun release() = scope.async {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }.await()

    companion object {
        fun createFromFile(path: String): WhisperContext {
            val ptr = WhisperLib.initContext(path)
            if (ptr == 0L) error("Kunne ikke laste Whisper-modell fra $path")
            return WhisperContext(ptr)
        }
    }
}

class WhisperLib {
    companion object {
        init {
            // Load the optimized variant first, fall back to default
            try {
                System.loadLibrary("whisper_v8fp16_va")
            } catch (e: UnsatisfiedLinkError) {
                System.loadLibrary("whisper")
            }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
    }
}
