package com.nous.ahcc.presentation.chat

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.domain.model.FlashcardDisplaySettings
import kotlinx.coroutines.delay

private val HintMuted = Color(0xFF8AA8A6)
private val Cream = Color(0xFFE8F4F3)
private val FlashcardChromeHeight = 44.dp

@Composable
fun FlashcardsScreen(viewModel: HermesChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prevOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (prevOrientation != null) {
                activity.requestedOrientation = prevOrientation
            } else {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    val display = state.flashcardDisplay
    FlashcardImmersiveEffect(
        active = !showSettings,
        systemBarColor = if (state.flashcardBlanked) Color.Black else display.backgroundColor,
    )

    BackHandler {
        if (showSettings) showSettings = false else viewModel.leaveFlashcards()
    }

    LaunchedEffect(state.flashcardBlanked) {
        if (state.flashcardBlanked && showSettings) {
            showSettings = false
        }
    }

    var secondsLeft by remember { mutableIntStateOf(display.blankAfterSeconds) }
    LaunchedEffect(
        state.flashcardIndex,
        state.flashcardBlanked,
        state.flashcardsActive,
        showSettings,
        display.blankAfterSeconds,
    ) {
        if (!state.flashcardsActive || state.flashcardBlanked || showSettings) return@LaunchedEffect
        val total = display.blankAfterSeconds.coerceIn(5, 300)
        var left = total
        while (left > 0) {
            secondsLeft = left
            delay(1_000)
            left--
        }
        viewModel.blankFlashcards()
    }

    val card = state.flashcardCards.getOrNull(state.flashcardIndex)

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.flashcardBlanked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(display.backgroundColor),
            ) {
                val enText = card?.en ?: "Нет карточки"
                val ruText = card?.ru
                val layout = rememberFlashcardResolvedLayout(
                    settings = display,
                    english = enText,
                    russian = ruText,
                    contentWidth = maxWidth - 48.dp,
                    contentHeight = maxHeight,
                    chromeHeight = FlashcardChromeHeight,
                )
                Column(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(layout.englishTopDp.dp))
                        Text(
                            text = enText,
                            color = display.englishColor,
                            fontSize = layout.englishSp.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = (layout.englishSp * 1.15f).sp,
                        )
                        if (ruText != null) {
                            Spacer(Modifier.height(layout.enRuGapDp.dp))
                            Text(
                                text = ruText,
                                color = display.russianColor,
                                fontSize = layout.russianSp.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = (layout.russianSp * 1.2f).sp,
                            )
                        }
                    }
                    FlashcardBottomChrome(
                        secondsLeft = secondsLeft,
                        onSettings = { showSettings = true },
                        onExit = { viewModel.leaveFlashcards() },
                    )
                }
            }
        }
    }

    if (showSettings) {
        FlashcardSettingsDialog(
            current = display,
            previewEn = card?.en ?: "to achieve",
            previewRu = card?.ru ?: "достигать, добиваться",
            onChange = viewModel::updateFlashcardDisplay,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun FlashcardBottomChrome(
    secondsLeft: Int,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlashcardChromeHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = secondsLeft.toString(),
            color = HintMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        FlashcardChromeIcon(onClick = onSettings) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Настройки",
                tint = HintMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        FlashcardChromeIcon(onClick = onExit) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Выйти из карточек",
                tint = HintMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FlashcardChromeIcon(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        content()
    }
}

@Composable
private fun FlashcardImmersiveEffect(active: Boolean, systemBarColor: Color) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    DisposableEffect(active, systemBarColor) {
        if (!active) {
            return@DisposableEffect onDispose { }
        }
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val prevStatus = window.statusBarColor
        val prevNav = window.navigationBarColor
        val bar = systemBarColor.toArgb()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = bar
        @Suppress("DEPRECATION")
        window.navigationBarColor = bar
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            window.statusBarColor = prevStatus
            @Suppress("DEPRECATION")
            window.navigationBarColor = prevNav
        }
    }
}

@Composable
private fun FlashcardSettingsDialog(
    current: FlashcardDisplaySettings,
    previewEn: String,
    previewRu: String,
    onChange: (FlashcardDisplaySettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF123D45),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Настройки карточек",
                        color = Cream,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    FlashcardChromeIcon(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть настройки",
                            tint = Cream,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
            Spacer(Modifier.height(12.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(draft.backgroundColor, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                val previewLayout = rememberFlashcardResolvedLayout(
                    settings = draft,
                    english = previewEn,
                    russian = previewRu,
                    contentWidth = maxWidth,
                    contentHeight = maxHeight,
                    chromeHeight = 0.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(previewLayout.englishTopDp.dp))
                    Text(
                        previewEn,
                        color = draft.englishColor,
                        fontSize = previewLayout.englishSp.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(previewLayout.enRuGapDp.dp))
                    Text(
                        previewRu,
                        color = draft.russianColor,
                        fontSize = previewLayout.russianSp.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("English — размер", color = Cream)
            Slider(
                value = draft.englishSp.toFloat(),
                onValueChange = {
                    val en = it.toInt()
                    draft = draft.copy(
                        englishSp = en,
                        russianSp = draft.targetRussianSp(en),
                    )
                    onChange(draft)
                },
                valueRange = 18f..72f,
                steps = 26,
            )
            Text(
                "Russian меньше на ${draft.russianSmallerPercent}% (≈ ${draft.russianSp} sp)",
                color = Cream,
            )
            Slider(
                value = draft.russianSmallerPercent.toFloat(),
                onValueChange = {
                    val pct = it.toInt()
                    draft = draft.copy(
                        russianSmallerPercent = pct,
                        russianSp = draft.copy(russianSmallerPercent = pct).targetRussianSp(draft.englishSp),
                    )
                    onChange(draft)
                },
                valueRange = 10f..55f,
                steps = 44,
            )
            Text("Russian — размер (цель)", color = Cream)
            Slider(
                value = draft.russianSp.toFloat(),
                onValueChange = {
                    val ru = it.toInt().coerceAtMost(draft.englishSp - 1)
                    draft = draft.copy(russianSp = ru.coerceAtLeast(10))
                    onChange(draft)
                },
                valueRange = 14f..48f,
                steps = 16,
            )
            Text("English — отступ сверху ${draft.englishTopPaddingDp} dp", color = Cream)
            Slider(
                value = draft.englishTopPaddingDp.toFloat(),
                onValueChange = {
                    draft = draft.copy(englishTopPaddingDp = it.toInt())
                    onChange(draft)
                },
                valueRange = 0f..120f,
                steps = 24,
            )
            Text("Между EN и RU ${draft.enRuGapDp} dp", color = Cream)
            Slider(
                value = draft.enRuGapDp.toFloat(),
                onValueChange = {
                    draft = draft.copy(enRuGapDp = it.toInt())
                    onChange(draft)
                },
                valueRange = 0f..80f,
                steps = 16,
            )
            Text(
                "Тёмный экран через ${draft.blankAfterSeconds} с",
                color = Cream,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = draft.blankAfterSeconds.toFloat(),
                onValueChange = {
                    draft = draft.copy(blankAfterSeconds = it.toInt())
                    onChange(draft)
                },
                valueRange = 5f..300f,
                steps = 58,
            )
            Text(
                "Пауза в озвучке ${draft.speechPauseSeconds} с",
                color = Cream,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = draft.speechPauseSeconds.toFloat(),
                onValueChange = {
                    draft = draft.copy(speechPauseSeconds = it.toInt())
                    onChange(draft)
                },
                valueRange = 0f..15f,
                steps = 15,
            )
            Text("Цвет English", color = Cream, modifier = Modifier.padding(top = 8.dp))
            ColorPresetRow(
                presets = FlashcardDisplaySettings.englishColorPresets,
                selected = draft.englishColorArgb,
                onSelect = {
                    draft = draft.copy(englishColorArgb = it)
                    onChange(draft)
                },
            )
            Text("Цвет Russian", color = Cream, modifier = Modifier.padding(top = 8.dp))
            ColorPresetRow(
                presets = FlashcardDisplaySettings.russianColorPresets,
                selected = draft.russianColorArgb,
                onSelect = {
                    draft = draft.copy(russianColorArgb = it)
                    onChange(draft)
                },
            )
            Text("Фон", color = Cream, modifier = Modifier.padding(top = 8.dp))
            ColorPresetRow(
                presets = FlashcardDisplaySettings.backgroundPresets,
                selected = draft.backgroundArgb,
                onSelect = {
                    draft = draft.copy(backgroundArgb = it)
                    onChange(draft)
                },
            )
            TextButton(
                onClick = {
                    draft = FlashcardDisplaySettings()
                    onChange(draft)
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Сброс", color = HintMuted)
            }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Готово", color = Cream)
                }
            }
        }
    }
}

@Composable
private fun ColorPresetRow(
    presets: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        presets.forEach { argb ->
            val color = Color(argb)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .border(
                        width = if (argb == selected) 3.dp else 1.dp,
                        color = if (argb == selected) Cream else HintMuted,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb) },
            )
        }
    }
}
