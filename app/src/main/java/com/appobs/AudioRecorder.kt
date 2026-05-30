package com.appobs

import android.media.MediaRecorder
import java.io.File

/**
 * Records a single voice note to a compressed .m4a file.
 *
 * We record to compact AAC rather than raw PCM because transcription happens
 * later off-device (see the laptop processing script); small files are what
 * matters in the field, since they have to be synced off the phone.
 */
class AudioRecorder {
    private var recorder: MediaRecorder? = null

    @Suppress("DEPRECATION") // MediaRecorder(Context) is API 31+; we support API 26
    fun start(output: File) {
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
    }

    /** Stops recording. Returns true if a valid file was produced. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // stop() throws if the recording was too short to produce any frames
            false
        } finally {
            r.release()
        }
    }
}
