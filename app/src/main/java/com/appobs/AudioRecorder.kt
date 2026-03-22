package com.appobs

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioRecorder {
    private var recorder: AudioRecord? = null
    private val sampleRate = 16000
    private val samples = mutableListOf<Float>()
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission")
    fun start() {
        samples.clear()
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).also { it.startRecording() }
        isRecording = true

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(samples) {
                        for (i in 0 until read) {
                            samples.add(buffer[i] / 32768f)
                        }
                    }
                }
            }
        }.start()
    }

    fun stop(): FloatArray {
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
        synchronized(samples) {
            return samples.toFloatArray()
        }
    }
}
