package com.nous.ahcc.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private val lock = Any()

    val isRecording: Boolean get() = synchronized(lock) { recorder != null }

    fun start(): File = synchronized(lock) {
        outputFile?.takeIf { recorder != null }?.let { existing ->
            Log.w(TAG, "already recording ${existing.name}, ignore start")
            return existing
        }
        stopInternal(delete = true)
        val dir = File(context.cacheDir, "ahcc_voice").also { it.mkdirs() }
        val name = "voice_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
        val file = File(dir, name)
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioEncodingBitRate(96_000)
        mr.setAudioSamplingRate(44_100)
        mr.setOutputFile(file.absolutePath)
        mr.prepare()
        mr.start()
        // Prime amplitude meter
        runCatching { mr.maxAmplitude }
        recorder = mr
        outputFile = file
        startedAtMs = System.currentTimeMillis()
        Log.i(TAG, "recording start ${file.name}")
        return file
    }

    /** 0…32767; MediaRecorder resets the peak each read. */
    fun currentAmplitude(): Int = synchronized(lock) {
        runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
    }

    data class Result(
        val file: File,
        val bytes: ByteArray,
        val durationMs: Long,
        val id: String = UUID.randomUUID().toString()
    )

    fun stop(): Result? = synchronized(lock) {
        val file = outputFile ?: return null
        val duration = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        return try {
            recorder?.apply {
                stop()
                release()
            }
            recorder = null
            outputFile = null
            if (!file.exists() || file.length() < 200L || duration < MIN_DURATION_MS) {
                file.delete()
                Log.w(TAG, "recording discarded duration=$duration size=${file.length()}")
                null
            } else {
                val bytes = file.readBytes()
                if (bytes.size > MAX_BYTES) {
                    file.delete()
                    error("Voice note too large (${bytes.size} bytes)")
                }
                Log.i(TAG, "recording stop ${file.name} ${bytes.size}B ${duration}ms")
                Result(file = file, bytes = bytes, durationMs = duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop failed: ${e.message}", e)
            runCatching { recorder?.release() }
            recorder = null
            outputFile = null
            file.delete()
            null
        }
    }

    fun cancel() = synchronized(lock) { stopInternal(delete = true) }

    private fun stopInternal(delete: Boolean) {
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        if (delete) outputFile?.delete()
        outputFile = null
    }

    companion object {
        private const val TAG = "AHCC-VoiceRec"
        const val MIN_DURATION_MS = 400L
        const val MAX_BYTES = (1.5 * 1024 * 1024).toInt()
        const val MIME = "audio/mp4"

        /** Peak amplitude treated as speech (MediaRecorder scale). */
        const val SPEECH_AMPLITUDE = 1_200
        const val SILENCE_AMPLITUDE = 800
        /** Continuous silence after speech → auto-stop. */
        const val SILENCE_END_MS = 1_400L
        /** Give up if user never speaks. */
        const val NO_SPEECH_TIMEOUT_MS = 8_000L
        /** Hard cap. */
        const val MAX_RECORDING_MS = 60_000L
    }
}
