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

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
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
        recorder = mr
        outputFile = file
        startedAtMs = System.currentTimeMillis()
        Log.i(TAG, "recording start ${file.name}")
        return file
    }

    data class Result(
        val file: File,
        val bytes: ByteArray,
        val durationMs: Long,
        val id: String = UUID.randomUUID().toString()
    )

    fun stop(): Result? {
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

    fun cancel() = stopInternal(delete = true)

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
    }
}
