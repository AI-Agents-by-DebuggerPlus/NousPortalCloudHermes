package com.nous.ahcc.presentation.chat

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.media.TtsVoiceChoice

@Composable
fun TtsVoiceScreen(
    viewModel: HermesChatViewModel,
    onBack: () -> Unit,
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refreshTtsVoices() }

    val field = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF123D45),
        contentColor = Color(0xFFE8F4F3),
    )
    val selected = ButtonDefaults.buttonColors(
        containerColor = Color(0xFF1FA6A0),
        contentColor = Color(0xFF0B1F2A),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F2A))
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFFE8F4F3))
            }
            Text("Голоса озвучки", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE8F4F3))
        }
        Text(
            text = state.ttsVoicesStatus.ifBlank { "Список голосов Google TTS" },
            color = Color(0xFF8AA8A6),
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = {
                val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                runCatching { context.startActivity(install) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Установить голосовые данные", color = Color(0xFFE8F4F3))
        }
        VoiceSection(
            title = "Английский",
            voices = state.ttsVoices.filter { it.language == "en" },
            selectedName = state.ttsEnglishVoice,
            colors = field,
            selectedColors = selected,
            onSelect = viewModel::selectTtsVoice,
            onPreview = viewModel::previewTtsVoice,
        )
        VoiceSection(
            title = "Русский",
            voices = state.ttsVoices.filter { it.language == "ru" },
            selectedName = state.ttsRussianVoice,
            colors = field,
            selectedColors = selected,
            onSelect = viewModel::selectTtsVoice,
            onPreview = viewModel::previewTtsVoice,
        )
    }
}

@Composable
private fun VoiceSection(
    title: String,
    voices: List<TtsVoiceChoice>,
    selectedName: String,
    colors: androidx.compose.material3.ButtonColors,
    selectedColors: androidx.compose.material3.ButtonColors,
    onSelect: (TtsVoiceChoice) -> Unit,
    onPreview: (TtsVoiceChoice) -> Unit,
) {
    Text(title, color = Color(0xFFE8F4F3), style = MaterialTheme.typography.titleMedium)
    if (voices.isEmpty()) {
        Text("Нет голосов", color = Color(0xFF8AA8A6))
        return
    }
    voices.forEach { voice ->
        val chosen = voice.name == selectedName
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onSelect(voice) },
                modifier = Modifier.weight(1f),
                colors = if (chosen) selectedColors else colors
            ) {
                Text(voice.label, maxLines = 2)
            }
            IconButton(onClick = { onPreview(voice) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Прослушать", tint = Color(0xFFE8F4F3))
            }
        }
    }
}
