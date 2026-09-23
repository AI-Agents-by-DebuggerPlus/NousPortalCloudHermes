package com.nous.ahcc.presentation.voice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nous.ahcc.AhccApp
import com.nous.ahcc.domain.model.ChatAttachment
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MediaKind
import com.nous.ahcc.media.MediaEnvelopeCodec
import com.nous.ahcc.media.VoicePlayer
import com.nous.ahcc.media.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoiceTestUiState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val isRecording: Boolean = false,
    val hasRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val isSending: Boolean = false,
    val fileName: String = "",
    val durationMs: Long = 0L,
    val byteSize: Int = 0,
    val agentReply: String = "",
    val status: String = "Idle",
    val notice: String? = null,
    val error: String? = null,
)

@Composable
fun VoiceTestScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val vm: VoiceTestViewModel = viewModel(factory = VoiceTestViewModelFactory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        val hub = (app as AhccApp).headsetHub
        hub.voiceGesturesEnabled = false
        onDispose {
            hub.voiceGesturesEnabled = true
            vm.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording()
        else vm.setError("Нужен доступ к микрофону")
    }

    fun startWithPermission() {
        val ok = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (ok) vm.startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(state.notice) {
        val n = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        vm.clearNotice()
    }
    LaunchedEffect(state.error) {
        val e = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(e)
        vm.clearError()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F2A))
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFFE8F4F3)
                )
            }
            Text(
                text = "Тест голоса → Hermes",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE8F4F3)
            )
        }

        Text(
            text = "1) Start/Stop запись  2) Play  3) Send → ответ агента ниже.\n" +
                "Нужен Connect в чате (Inference).",
            color = Color(0xFF8AA8A6),
            style = MaterialTheme.typography.bodySmall
        )

        Text(
            text = "Связь: ${state.connectionState} · ${state.status}",
            color = if (state.connectionState == ConnectionState.Connected) {
                Color(0xFF4CAF50)
            } else {
                Color(0xFFEF5350)
            },
            style = MaterialTheme.typography.bodyMedium
        )

        if (state.hasRecording) {
            Text(
                text = "Файл: ${state.fileName} · ${state.byteSize} B · ${state.durationMs} ms",
                color = Color(0xFFE8F4F3),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.isRecording) {
                Button(
                    onClick = { vm.stopRecording() },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { startWithPermission() },
                    enabled = !state.isSending,
                    modifier = Modifier.weight(1f)
                ) { Text("Start") }
            }
            OutlinedButton(
                onClick = { vm.playOrStop() },
                enabled = state.hasRecording && !state.isRecording && !state.isSending,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (state.isPlaying) "Stop play" else "Play")
            }
        }

        Button(
            onClick = { vm.sendToHermes() },
            enabled = state.hasRecording &&
                !state.isRecording &&
                !state.isSending &&
                state.connectionState == ConnectionState.Connected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isSending) "Sending…" else "Send to Hermes Cloud")
        }

        Text(
            text = "Ответ агента",
            color = Color(0xFF8AA8A6),
            style = MaterialTheme.typography.labelLarge
        )
        val replyScroll = rememberScrollState()
        LaunchedEffect(state.agentReply.length) {
            if (state.agentReply.isEmpty()) return@LaunchedEffect
            delay(32)
            replyScroll.animateScrollTo(replyScroll.maxValue)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF123D45))
                .padding(12.dp)
                .verticalScroll(replyScroll)
        ) {
            Text(
                text = state.agentReply.ifBlank { "—" },
                color = Color(0xFFE8F4F3),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        SnackbarHost(hostState = snackbar)
    }
}

class VoiceTestViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val app = application as AhccApp
    private val recorder = VoiceRecorder(application)
    private val player = VoicePlayer()

    private var lastBytes: ByteArray? = null
    private var lastPath: String? = null
    private var lastDurationMs: Long = 0L
    private var lastName: String = ""
    private var lastId: String = ""

    private val _state = MutableStateFlow(VoiceTestUiState())
    val state: StateFlow<VoiceTestUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.chatGateway.connectionState.collect { cs ->
                _state.update { it.copy(connectionState = cs) }
            }
        }
        viewModelScope.launch {
            app.chatGateway.responses.collect { response ->
                when (response.event) {
                    "token", "message.delta" -> {
                        val delta = response.delta ?: response.content.orEmpty()
                        if (delta.isNotEmpty()) {
                            _state.update { it.copy(agentReply = it.agentReply + delta) }
                        }
                    }
                    "done", "message.complete" -> {
                        val text = response.content?.takeIf { it.isNotBlank() } ?: _state.value.agentReply
                        Log.i(TAG, "reply done chars=${text.length} preview=${text.take(160).replace('\n', ' ')}")
                        _state.update {
                            it.copy(
                                isSending = false,
                                status = "Done (${text.length} chars)",
                                agentReply = text
                            )
                        }
                    }
                    "error" -> {
                        _state.update {
                            it.copy(
                                isSending = false,
                                status = "Error",
                                error = response.error ?: "Agent error"
                            )
                        }
                    }
                    "message" -> {
                        val content = response.content
                            ?: response.data?.content
                            ?: response.delta
                            ?: return@collect
                        _state.update {
                            it.copy(
                                isSending = false,
                                status = "Done",
                                agentReply = content
                            )
                        }
                    }
                }
            }
        }
    }

    fun startRecording() {
        if (_state.value.isRecording) return
        player.stop()
        runCatching {
            recorder.start()
            lastBytes = null
            lastPath = null
            _state.update {
                it.copy(
                    isRecording = true,
                    hasRecording = false,
                    isPlaying = false,
                    status = "Recording…",
                    agentReply = "",
                    error = null
                )
            }
            Log.i(TAG, "start recording")
        }.onFailure { e ->
            setError(e.message ?: "Start failed")
        }
    }

    fun stopRecording() {
        if (!_state.value.isRecording) return
        val result = recorder.stop()
        if (result == null) {
            _state.update {
                it.copy(isRecording = false, hasRecording = false, status = "Too short")
            }
            setError("Запись слишком короткая")
            return
        }
        lastBytes = result.bytes
        lastPath = result.file.absolutePath
        lastDurationMs = result.durationMs
        lastName = result.file.name
        lastId = result.id
        _state.update {
            it.copy(
                isRecording = false,
                hasRecording = true,
                fileName = result.file.name,
                durationMs = result.durationMs,
                byteSize = result.bytes.size,
                status = "Recorded"
            )
        }
        Log.i(TAG, "stopped ${result.file.name} ${result.bytes.size}B")
    }

    fun playOrStop() {
        val path = lastPath ?: return
        if (_state.value.isPlaying) {
            player.stop()
            _state.update { it.copy(isPlaying = false, status = "Stopped play") }
            return
        }
        player.play(path) {
            _state.update { it.copy(isPlaying = false, status = "Play finished") }
        }
        _state.update { it.copy(isPlaying = true, status = "Playing…") }
    }

    fun sendToHermes() {
        val bytes = lastBytes
        val path = lastPath
        if (bytes == null || path == null) {
            setError("Нет записи — сначала Start/Stop")
            return
        }
        if (_state.value.connectionState != ConnectionState.Connected) {
            setError("Сначала Connect в чате")
            return
        }
        if (_state.value.isSending) return
        sendInternal(bytes, path)
    }

    private fun sendInternal(bytes: ByteArray, path: String) {
        viewModelScope.launch {
            try {
                _state.update {
                    it.copy(isSending = true, agentReply = "", status = "Sending…", notice = null)
                }
                val sessionId = app.preferences.configFlow.first().sessionId
                val attachment = ChatAttachment(
                    id = lastId.ifBlank { java.util.UUID.randomUUID().toString() },
                    kind = MediaKind.Voice,
                    mime = VoiceRecorder.MIME,
                    name = lastName.ifBlank { "voice.m4a" },
                    bytes = bytes,
                    durationMs = lastDurationMs,
                    localPath = path
                )
                val caption = "Голосовое сообщение (${lastDurationMs} ms)"
                val wires = MediaEnvelopeCodec.wiresOf(listOf(attachment))
                Log.i(
                    TAG,
                    "send ${bytes.size}B attachments=${wires.size} captionChars=${caption.length} session=$sessionId"
                )
                withContext(Dispatchers.IO) {
                    app.chatGateway.sendMedia(
                        envelopeText = "",
                        historyPlaceholder = caption,
                        photoJpegBase64 = null,
                        attachmentWires = wires,
                        sessionId = sessionId
                    )
                }
                _state.update {
                    it.copy(notice = "Аудиофайл отправлен", status = "Sent, waiting reply…")
                }
            } catch (e: Exception) {
                Log.e(TAG, "send failed: ${e.message}", e)
                _state.update {
                    it.copy(isSending = false, status = "Send failed", error = e.message)
                }
            }
        }
    }

    fun setError(msg: String) = _state.update { it.copy(error = msg) }
    fun clearError() = _state.update { it.copy(error = null) }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    fun release() {
        recorder.cancel()
        player.stop()
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AHCC-VoiceTest"
    }
}

class VoiceTestViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VoiceTestViewModel::class.java)) {
            return VoiceTestViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel")
    }
}
