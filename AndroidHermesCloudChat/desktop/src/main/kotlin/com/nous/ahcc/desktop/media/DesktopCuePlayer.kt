package com.nous.ahcc.desktop.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Short UI cues for AHCC Desktop: tool progress vs finished agent reply.
 * Generated PCM (no asset files).
 */
object DesktopCuePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var lastToolAtMs = 0L

    private const val SAMPLE_RATE = 22_050f
    private const val TOOL_THROTTLE_MS = 1_800L

    /** Soft mid tick — Hermes tool / terminal card update. */
    fun playTool() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastToolAtMs < TOOL_THROTTLE_MS) return
            lastToolAtMs = now
        }
        scope.launch {
            playSequence(
                listOf(
                    Tone(880.0, 42, 0.16),
                    Tone(660.0, 36, 0.12),
                ),
                gapMs = 18,
            )
        }
    }

    /** Clearer chime — finished prose reply from the agent. */
    fun playReply() {
        scope.launch {
            playSequence(
                listOf(
                    Tone(523.25, 85, 0.20),
                    Tone(659.25, 100, 0.24),
                    Tone(783.99, 130, 0.26),
                ),
                gapMs = 28,
            )
        }
    }

    fun looksLikeToolProgress(text: String): Boolean {
        if (text.contains("💻") || text.contains("✍️") || text.contains("📖") ||
            text.contains("📚") || text.contains("🔧") || text.contains("↪")
        ) {
            return true
        }
        if (text.contains("Writing ", ignoreCase = true) ||
            text.contains("Reading ", ignoreCase = true) ||
            text.contains("/opt/hermes/", ignoreCase = true) ||
            text.contains("/opt/data/cache/", ignoreCase = true) ||
            text.contains("```shell", ignoreCase = true)
        ) {
            return true
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val toolish = lines.count { line ->
            line.startsWith("💻") || line.startsWith("✍️") || line.startsWith("terminal", ignoreCase = true) ||
                line.startsWith("/opt/") || line.startsWith("date +")
        }
        return toolish * 2 >= lines.size
    }

    fun hasSubstantialProse(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val prose = lines.count { line ->
            !isHermesStatusLine(line) &&
                !line.startsWith("💻") && !line.startsWith("✍️") && !line.startsWith("📖") &&
                !line.startsWith("📚") && !line.startsWith("🔧") && !line.startsWith("↪") &&
                !line.startsWith("```") && !line.startsWith("TO:", ignoreCase = true) &&
                !line.startsWith("_") &&
                !line.contains("Writing ", ignoreCase = true) &&
                !line.startsWith("terminal", ignoreCase = true) &&
                !line.startsWith("/opt/") && line.length > 40
        }
        return prose >= 1
    }

    fun looksLikeStillWorking(text: String): Boolean {
        if (looksLikeToolProgress(text)) return true
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        if (lines.all { isHermesStatusLine(it) }) return true
        return lines.any { isHermesStatusLine(it) } && !hasSubstantialProse(text)
    }

    private fun isHermesStatusLine(line: String): Boolean {
        val t = line.lowercase()
        return t.contains("agent is running") ||
            t.contains("working —") || t.contains("working -") ||
            t.contains("mid-turn") ||
            t.contains("runtime footer") ||
            t.contains("/memory can't") ||
            t.contains("takes effect on next message") ||
            t.contains("iteration ") ||
            line.startsWith("⏳") || line.startsWith("📎 Runtime")
    }

    private data class Tone(val hz: Double, val ms: Int, val volume: Double)

    private fun playSequence(tones: List<Tone>, gapMs: Int) {
        runCatching {
            val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) return
            val line = AudioSystem.getLine(info) as SourceDataLine
            line.open(format)
            line.start()
            try {
                tones.forEachIndexed { index, tone ->
                    val pcm = sinePcm(tone.hz, tone.ms, tone.volume)
                    line.write(pcm, 0, pcm.size)
                    if (index < tones.lastIndex && gapMs > 0) {
                        val silence = ByteArray((SAMPLE_RATE * gapMs / 1000f).toInt() * 2)
                        line.write(silence, 0, silence.size)
                    }
                }
                line.drain()
            } finally {
                line.stop()
                line.close()
            }
        }
    }

    private fun sinePcm(hz: Double, ms: Int, volume: Double): ByteArray {
        val n = (SAMPLE_RATE * ms / 1000f).toInt().coerceAtLeast(1)
        val bytes = ByteArray(n * 2)
        val amp = (Short.MAX_VALUE * volume.coerceIn(0.0, 0.45)).toInt()
        val fade = min(n / 8, 400)
        for (i in 0 until n) {
            val env = when {
                i < fade -> i.toDouble() / fade
                i > n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            val sample = (sin(2.0 * PI * hz * i / SAMPLE_RATE) * amp * env).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
