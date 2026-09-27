package com.nous.ahcc.presentation.chat

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private val Navy = Color(0xFF0B1F2A)
private val Cream = Color(0xFFE8F4F3)
private val Muted = Color(0xFF8AA8A6)

@Composable
fun FlashcardsScreen(viewModel: HermesChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BackHandler { viewModel.leaveFlashcards() }

    LaunchedEffect(state.flashcardBlanked, state.flashcardIndex, state.flashcardsActive) {
        if (state.flashcardsActive && !state.flashcardBlanked) {
            delay(60_000)
            viewModel.blankFlashcards()
        }
    }

    if (state.flashcardBlanked) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {}
        return
    }

    val card = state.flashcardCards.getOrNull(state.flashcardIndex)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = card?.en ?: "Нет карточки",
            color = Cream,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            lineHeight = 36.sp,
        )
        if (card != null) {
            Text(
                text = card.ru,
                color = Muted,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        Text(
            text = "Play — показать · 2× — следующая · 3× — выход",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 36.dp),
        )
        TextButton(onClick = { viewModel.leaveFlashcards() }) {
            Text("Закрыть", color = Cream)
        }
    }
}
