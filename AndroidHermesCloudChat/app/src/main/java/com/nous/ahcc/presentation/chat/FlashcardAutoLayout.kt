package com.nous.ahcc.presentation.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nous.ahcc.domain.model.FlashcardDisplaySettings
import kotlin.math.floor
import kotlin.math.roundToInt

data class FlashcardResolvedLayout(
    val englishSp: Int,
    val russianSp: Int,
    val englishTopDp: Int,
    val enRuGapDp: Int,
)

@Composable
fun rememberFlashcardResolvedLayout(
    settings: FlashcardDisplaySettings,
    english: String,
    russian: String?,
    contentWidth: Dp,
    contentHeight: Dp,
    chromeHeight: Dp,
): FlashcardResolvedLayout {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widthPx = with(density) { contentWidth.roundToPx() }.coerceAtLeast(1)
    val maxHeightPx = with(density) { (contentHeight - chromeHeight).roundToPx() }.coerceAtLeast(1)
    val dpToPx: (Int) -> Int = { dp -> with(density) { dp.dp.roundToPx() } }

    return remember(
        settings,
        english,
        russian,
        widthPx,
        maxHeightPx,
        density.density,
    ) {
        resolveFlashcardLayout(
            settings = settings,
            english = english,
            russian = russian,
            widthPx = widthPx,
            maxHeightPx = maxHeightPx,
            dpToPx = dpToPx,
            measure = { text, sp, lineMult ->
                if (text.isBlank()) 0
                else {
                    val size = sp.coerceAtLeast(8)
                    textMeasurer.measure(
                        text = text,
                        style = TextStyle(
                            fontSize = size.sp,
                            lineHeight = (size * lineMult).sp,
                        ),
                        constraints = Constraints(maxWidth = widthPx),
                    ).size.height
                }
            },
        )
    }
}

internal fun resolveFlashcardLayout(
    settings: FlashcardDisplaySettings,
    english: String,
    russian: String?,
    widthPx: Int,
    maxHeightPx: Int,
    dpToPx: (Int) -> Int,
    measure: (text: String, sp: Int, lineHeightMult: Float) -> Int,
): FlashcardResolvedLayout {
    val percent = settings.russianSmallerPercent.coerceIn(10, 55)
    val ruRatio = (100 - percent) / 100f
    var topDp = settings.englishTopPaddingDp.coerceIn(0, 160)
    var gapDp = settings.enRuGapDp.coerceIn(0, 120)
    var enSp = settings.englishSp.coerceIn(14, 72)

    fun ruFor(en: Int): Int =
        floor(en * ruRatio).roundToInt().coerceIn(10, en - 1)

    fun totalHeight(en: Int, ru: Int, top: Int, gap: Int): Int {
        val enH = measure(english, en, 1.15f)
        val ruH = if (russian.isNullOrBlank()) 0 else measure(russian, ru, 1.2f)
        return dpToPx(top) + enH + dpToPx(gap) + ruH
    }

    var ruSp = ruFor(enSp)
    var attempts = 0
    while (totalHeight(enSp, ruSp, topDp, gapDp) > maxHeightPx && attempts < 240) {
        attempts++
        when {
            enSp > 14 -> {
                enSp--
                ruSp = ruFor(enSp)
            }
            gapDp > 0 -> gapDp = (gapDp * 0.85f).roundToInt()
            topDp > 0 -> topDp = (topDp * 0.85f).roundToInt()
            ruSp > 10 && ruSp >= enSp -> ruSp = (enSp - 1).coerceAtLeast(10)
            else -> break
        }
    }

    if (ruSp >= enSp) ruSp = (enSp - 1).coerceAtLeast(10)

    return FlashcardResolvedLayout(
        englishSp = enSp,
        russianSp = ruSp,
        englishTopDp = topDp,
        enRuGapDp = gapDp,
    )
}
