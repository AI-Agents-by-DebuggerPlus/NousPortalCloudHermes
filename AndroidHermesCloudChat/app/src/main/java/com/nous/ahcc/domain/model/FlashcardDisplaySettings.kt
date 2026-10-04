package com.nous.ahcc.domain.model

import androidx.compose.ui.graphics.Color

data class FlashcardDisplaySettings(
    val englishSp: Int = 32,
    val russianSp: Int = 22,
    val englishColorArgb: Int = 0xFFE8F4F3.toInt(),
    val russianColorArgb: Int = 0xFF8AA8A6.toInt(),
    val backgroundArgb: Int = 0xFF000000.toInt(),
    /** Seconds on screen before auto blank (dark mode). */
    val blankAfterSeconds: Int = 60,
    /** Pause between TTS steps (EN→RU→EN→EN), not after the last English. */
    val speechPauseSeconds: Int = 3,
    val englishTopPaddingDp: Int = 8,
    val enRuGapDp: Int = 24,
    /** Target: Russian ≈ English × (100 − N) / 100; auto-fit may shrink further. */
    val russianSmallerPercent: Int = 31,
) {
    fun targetRussianSp(englishSp: Int): Int {
        val ratio = (100 - russianSmallerPercent.coerceIn(10, 55)) / 100f
        return kotlin.math.floor(englishSp * ratio).toInt().coerceIn(10, englishSp - 1)
    }
    val englishColor: Color get() = Color(englishColorArgb)
    val russianColor: Color get() = Color(russianColorArgb)
    val backgroundColor: Color get() = Color(backgroundArgb)

    companion object {
        val englishColorPresets = listOf(
            0xFFE8F4F3.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF7DD3FC.toInt(),
            0xFFFBBF24.toInt(),
        )
        val russianColorPresets = listOf(
            0xFF8AA8A6.toInt(),
            0xFFE8F4F3.toInt(),
            0xFFA7F3D0.toInt(),
            0xFFF9A8D4.toInt(),
        )
        val backgroundPresets = listOf(
            0xFF000000.toInt(),
            0xFF0B1F2A.toInt(),
            0xFF123D45.toInt(),
            0xFF1A1A1A.toInt(),
        )
    }
}
