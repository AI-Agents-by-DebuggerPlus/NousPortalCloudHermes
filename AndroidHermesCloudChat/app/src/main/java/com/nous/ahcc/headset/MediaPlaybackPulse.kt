package com.nous.ahcc.headset

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.sin

/**
 * Short USAGE_MEDIA pulse so the system assigns media-button routing to this app.
 */
object MediaPlaybackPulse {
    private const val TAG = "AHCC-BT-Pulse"

    fun pulse(context: Context) {
        var track: AudioTrack? = null
        try {
            val sampleRate = 44_100
            val durationMs = 180
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)
            val amplitude = 400
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                buffer[i] = (sin(2.0 * Math.PI * 440.0 * t) * amplitude).toInt().toShort()
            }

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, buffer.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val am = context.getSystemService(AudioManager::class.java)
                val a2dp = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                if (a2dp != null) {
                    track.setPreferredDevice(a2dp)
                    Log.i(TAG, "pulse preferred A2DP ${a2dp.productName}")
                }
            }

            track.write(buffer, 0, buffer.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 40L)
            Log.i(TAG, "USAGE_MEDIA pulse ${durationMs}ms")
        } catch (e: Exception) {
            Log.w(TAG, "pulse failed: ${e.message}")
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
        }
    }
}
