package com.nous.ahcc.presentation.voice

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nous.ahcc.AhccApp
import com.nous.ahcc.headset.HeadsetMonitorService
import com.nous.ahcc.media.SpeechTranscriber
import com.nous.ahcc.media.SpeechTranscript
import com.nous.ahcc.presentation.chat.HermesChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFFE8F4F3)
private val Muted = Color(0xFF8AA8A6)
private val Deep = Color(0xFF0B1F2A)
private val Panel = Color(0xFF123D45)
private val Accent = Color(0xFF1FA6A0)

data class SttTestUiState(
    val locale: String = "ru-RU",
    val listening: Boolean = false,
    val status: String = "",
    val lastText: String = "",
    val eventLog: List<String> = emptyList(),
)

@Composable
fun SttTestScreen(
    chatViewModel: HermesChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AhccApp
    val vm: SttTestViewModel = viewModel(factory = SttTestViewModelFactory(app))
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        HeadsetMonitorService.start(context)
        app.headsetHub.voiceGesturesEnabled = false
        onDispose {
            vm.cancel()
            app.headsetHub.voiceGesturesEnabled = true
        }
    }
    LaunchedEffect(Unit) {
        app.headsetHub.events.collect { event ->
            val label = event.label
            if (label.equals("Play", ignoreCase = true) || label.equals("HeadsetHook", ignoreCase = true)) {
                chatViewModel.stopSpokenReply()
                vm.toggle()
            }
        }
    }

    val buttonColors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Ink)
    val selectedColors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Deep)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Deep)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Text("Тест транскрибации", style = MaterialTheme.typography.headlineSmall, color = Ink)
        }
        Text(
            text = "Нажмите Play на гарнитуре или «Слушать». Распознавание через Google SpeechRecognizer.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Локаль: ${state.locale}", color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.setLocale("ru-RU") },
                enabled = !state.listening,
                colors = if (state.locale == "ru-RU") selectedColors else buttonColors,
            ) { Text("Русский") }
            Button(
                onClick = { vm.setLocale("en-US") },
                enabled = !state.listening,
                colors = if (state.locale == "en-US") selectedColors else buttonColors,
            ) { Text("English") }
        }
        Text(
            text = state.status.ifBlank { "Готов к распознаванию" },
            color = if (state.listening) Accent else Ink,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = state.lastText.ifBlank { "—" },
            color = Ink,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    chatViewModel.stopSpokenReply()
                    vm.start()
                },
                enabled = !state.listening,
                colors = buttonColors,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("Слушать", modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = vm::cancel,
                enabled = state.listening,
                colors = buttonColors,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.MicOff, contentDescription = null)
                Text("Отмена", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(onClick = vm::clearLog, colors = buttonColors, modifier = Modifier.fillMaxWidth()) {
            Text("Очистить журнал")
        }
        Text("Журнал распознавания", color = Ink, style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.eventLog.isEmpty()) {
                item { Text("Пока нет результатов", color = Muted, style = MaterialTheme.typography.bodySmall) }
            } else {
                items(state.eventLog) { line ->
                    Text(line, color = Ink, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

class SttTestViewModel(application: Application) : AndroidViewModel(application) {
    private val transcriber = SpeechTranscriber(application)
    private val _state = MutableStateFlow(SttTestUiState())
    val state: StateFlow<SttTestUiState> = _state.asStateFlow()

    fun setLocale(locale: String) {
        if (_state.value.listening) return
        _state.update { it.copy(locale = locale) }
    }

    fun toggle() {
        if (_state.value.listening) cancel() else start()
    }

    fun start() {
        val locale = _state.value.locale
        _state.update { it.copy(listening = true, status = "Слушаю ($locale)…") }
        transcriber.start(locale, ::onResult)
    }

    fun cancel() {
        transcriber.cancel()
        if (_state.value.listening) {
            _state.update { it.copy(listening = false, status = "Отменено") }
        }
    }

    fun clearLog() {
        _state.update { it.copy(eventLog = emptyList(), lastText = "", status = "") }
    }

    private fun onResult(result: SpeechTranscript) {
        val at = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val (status, lastText, logLine) = when (result) {
            is SpeechTranscript.Success -> Triple("OK", result.text, "$at  OK  «${result.text}»")
            SpeechTranscript.Empty -> Triple("Пусто (нет речи)", "", "$at  EMPTY")
            SpeechTranscript.Cancelled -> Triple("Отменено", _state.value.lastText, "$at  CANCELLED")
            is SpeechTranscript.Error -> Triple(result.message, "", "$at  ERR  ${result.message}")
        }
        _state.update { current ->
            current.copy(
                listening = false,
                status = status,
                lastText = lastText.ifBlank { current.lastText },
                eventLog = (listOf(logLine) + current.eventLog).take(40),
            )
        }
    }

    override fun onCleared() {
        transcriber.shutdown()
    }
}

class SttTestViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SttTestViewModel::class.java)) {
            return SttTestViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
