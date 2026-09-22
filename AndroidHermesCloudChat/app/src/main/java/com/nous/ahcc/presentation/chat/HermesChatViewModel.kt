package com.nous.ahcc.presentation.chat

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nous.ahcc.AhccApp
import com.nous.ahcc.data.HermesChatGateway
import com.nous.ahcc.data.local.ConnectionPreferences
import com.nous.ahcc.domain.model.ChatAttachment
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MediaKind
import com.nous.ahcc.domain.model.MessageRole
import com.nous.ahcc.domain.model.TransportMode
import com.nous.ahcc.headset.HeadsetButtonNames
import com.nous.ahcc.headset.HeadsetMonitorService
import com.nous.ahcc.media.MediaEnvelopeCodec
import com.nous.ahcc.media.MediaLimits
import com.nous.ahcc.media.PhotoCompressor
import com.nous.ahcc.media.VoicePlayer
import com.nous.ahcc.media.VoiceRecorder
import com.nous.ahcc.data.supabase.AhccSupabaseClient
import com.nous.ahcc.service.HermesConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val config: ConnectionConfig = ConnectionConfig(),
    val draft: String = "",
    val lastError: String? = null,
    val isSending: Boolean = false,
    val isRecording: Boolean = false,
    val playingPath: String? = null,
    val headsetCaptureOn: Boolean = false
)

class HermesChatViewModel(
    application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : AndroidViewModel(application) {

    private val app = application as AhccApp
    private val voiceRecorder = VoiceRecorder(application)
    private val voicePlayer = VoicePlayer()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        viewModelScope.launch {
            chatGateway.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
                if (state == ConnectionState.Connected) {
                    ensureHeadsetCapture()
                }
            }
        }
        viewModelScope.launch {
            chatGateway.lastError.collect { error ->
                _uiState.update { it.copy(lastError = error) }
            }
        }
        viewModelScope.launch {
            chatGateway.responses.collect { response ->
                handleIncoming(response)
            }
        }
        viewModelScope.launch {
            app.headsetHub.state.collect { hs ->
                _uiState.update { it.copy(headsetCaptureOn = hs.captureOn) }
            }
        }
        viewModelScope.launch {
            app.headsetHub.events.collect { event ->
                onHeadsetEvent(event.label)
            }
        }
    }

    private fun ensureHeadsetCapture() {
        HeadsetMonitorService.start(getApplication())
    }

    private fun onHeadsetEvent(label: String) {
        if (!HeadsetButtonNames.isBtPlayGestureLabel(label)) return
        // Only committed Play reaches hub as Play counter; Next is separate.
        // Hub already maps double-tap to Next events — we only get Play after single-tap window
        // via notifyButton for play gestures that commit... Actually play gestures go through
        // pending window; only recordPlayEvent emits. But events SharedFlow emits only from
        // recordPlay/Next/Other. So MEDIA_PLAY-like labels won't appear on events for pending.
        // recordPlayEvent emits display "Play". recordNextEvent emits "Next" / "Next (2×Play)".
        if (label.startsWith("Next", ignoreCase = true)) return
        if (label.contains("Play", ignoreCase = true) ||
            label.equals("HeadsetHook", ignoreCase = true) ||
            label.equals("Pause", ignoreCase = true) ||
            label.equals("Play/Pause", ignoreCase = true)
        ) {
            toggleVoiceRecording()
        }
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun updateConfig(config: ConnectionConfig) {
        viewModelScope.launch {
            preferences.save(config)
        }
    }

    fun connect(forceNewSession: Boolean = false) {
        val config = _uiState.value.config
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), lastError = null, isSending = false) }
            try {
                val sb = AhccSupabaseClient(config.supabaseUrl, config.supabaseAnonKey)
                val resolved = withContext(Dispatchers.IO) {
                    sb.resolveOrCreateSession(
                        forceNew = forceNewSession,
                        preferredId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_ANDROID,
                    )
                }
                val next = config.copy(sessionId = resolved)
                preferences.save(next)
                _uiState.update { it.copy(config = next) }

                if (sb.isConfigured) {
                    val rows = withContext(Dispatchers.IO) { sb.fetchSessionMessages(resolved) }
                    val loaded = rows.mapNotNull { row ->
                        when {
                            row.content.equals(AhccSupabaseClient.NEW_SESSION_CONTENT, ignoreCase = true) ->
                                ChatMessage(
                                    id = row.id ?: UUID.randomUUID().toString(),
                                    role = MessageRole.System,
                                    content = "Session ${row.sessionId}"
                                )
                            AhccSupabaseClient.parseFileMarker(row.content) != null -> {
                                val ref = AhccSupabaseClient.parseFileMarker(row.content)!!
                                ChatMessage(
                                    id = row.id ?: UUID.randomUUID().toString(),
                                    role = if (row.senderName.equals(AhccSupabaseClient.SENDER_HERMES, ignoreCase = true))
                                        MessageRole.Assistant else MessageRole.User,
                                    content = "[file] ${ref.second}",
                                    attachmentKind = MediaKind.File,
                                    attachmentName = ref.second
                                )
                            }
                            row.senderName.equals(AhccSupabaseClient.SENDER_HERMES, ignoreCase = true) ->
                                ChatMessage(
                                    id = row.id ?: UUID.randomUUID().toString(),
                                    role = MessageRole.Assistant,
                                    content = row.content
                                )
                            else ->
                                ChatMessage(
                                    id = row.id ?: UUID.randomUUID().toString(),
                                    role = MessageRole.User,
                                    content = row.content
                                )
                        }
                    }
                    _uiState.update { it.copy(messages = loaded) }
                }

                if (next.keepAliveInBackground && next.transport == TransportMode.WebSocket) {
                    HermesConnectionService.start(getApplication())
                }
                chatGateway.connect(next)
                ensureHeadsetCapture()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lastError = e.message,
                        messages = listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.System,
                                content = e.message ?: "Connect / Supabase failed"
                            )
                        )
                    )
                }
            }
        }
    }

    fun newSession() = connect(forceNewSession = true)

    fun disconnect() {
        HermesConnectionService.stop(getApplication())
        chatGateway.disconnect()
        voiceRecorder.cancel()
        voicePlayer.stop()
        _uiState.update { it.copy(isRecording = false, playingPath = null) }
    }

    fun sendPrompt(userPrompt: String = _uiState.value.draft.trim()) {
        if (userPrompt.isBlank()) return
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            _uiState.update { it.copy(lastError = "Not connected") }
            return
        }
        beginSend(
            userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.User,
                content = userPrompt
            )
        ) {
            val cfg = _uiState.value.config
            withContext(Dispatchers.IO) {
                AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                    sessionId = cfg.sessionId,
                    senderName = AhccSupabaseClient.SENDER_ANDROID,
                    content = userPrompt,
                    recipientName = AhccSupabaseClient.SENDER_HERMES,
                )
            }
            chatGateway.sendMessage(userPrompt, cfg.sessionId)
        }
    }

    fun toggleVoiceRecording() {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            _uiState.update { it.copy(lastError = "Подключитесь, чтобы отправить голос") }
            return
        }
        if (voiceRecorder.isRecording) {
            stopVoiceAndSend()
        } else {
            runCatching {
                voiceRecorder.start()
                _uiState.update { it.copy(isRecording = true) }
            }.onFailure { e ->
                _uiState.update { it.copy(lastError = e.message ?: "Не удалось начать запись") }
            }
        }
    }

    private fun stopVoiceAndSend() {
        val result = voiceRecorder.stop()
        _uiState.update { it.copy(isRecording = false) }
        if (result == null) {
            _uiState.update { it.copy(lastError = "Запись слишком короткая") }
            return
        }
        val attachment = ChatAttachment(
            id = result.id,
            kind = MediaKind.Voice,
            mime = VoiceRecorder.MIME,
            name = result.file.name,
            bytes = result.bytes,
            durationMs = result.durationMs,
            localPath = result.file.absolutePath
        )
        sendAttachments(listOf(attachment), caption = _uiState.value.draft.trim().ifBlank { null })
        _uiState.update { it.copy(draft = "") }
    }

    fun onCameraPhotoCaptured(file: File) {
        viewModelScope.launch {
            try {
                val (bytes, name) = withContext(Dispatchers.IO) {
                    PhotoCompressor.compressFile(file)
                }
                file.delete()
                val attachment = ChatAttachment(
                    id = PhotoCompressor.newAttachmentId(),
                    kind = MediaKind.Photo,
                    mime = PhotoCompressor.MIME,
                    name = name,
                    bytes = bytes
                )
                sendAttachments(listOf(attachment), caption = _uiState.value.draft.trim().ifBlank { null })
                _uiState.update { it.copy(draft = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = e.message ?: "Ошибка фото") }
            }
        }
    }

    fun onFilePicked(uri: Uri, displayName: String?, mime: String?) {
        viewModelScope.launch {
            try {
                val name = displayName?.ifBlank { null } ?: "file_${System.currentTimeMillis()}"
                val lower = name.lowercase()
                val resolvedMime = mime?.ifBlank { null } ?: "application/octet-stream"
                val isTextShare = lower.endsWith(".md") || lower.endsWith(".markdown") ||
                    lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv") ||
                    resolvedMime.startsWith("text/") || resolvedMime == "application/json"

                if (isTextShare) {
                    val cfg = _uiState.value.config
                    val text = withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Не удалось прочитать файл")
                    }
                    val outMime = when {
                        lower.endsWith(".md") || lower.endsWith(".markdown") -> "text/markdown"
                        lower.endsWith(".json") || resolvedMime == "application/json" -> "application/json"
                        lower.endsWith(".csv") -> "text/csv"
                        else -> "text/plain"
                    }
                    val marker = withContext(Dispatchers.IO) {
                        AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).uploadTextFile(
                            sessionId = cfg.sessionId,
                            senderName = AhccSupabaseClient.SENDER_ANDROID,
                            fileName = name,
                            text = text,
                            mime = outMime,
                            recipientName = AhccSupabaseClient.SENDER_DESKTOP,
                        )
                    }
                    val label = AhccSupabaseClient.parseFileMarker(marker)?.second ?: name
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.User,
                                content = "[file] $label",
                                attachmentKind = MediaKind.File,
                                attachmentName = label
                            ),
                            draft = ""
                        )
                    }
                    // Optional short note to Inference/Hermes.
                    if (_uiState.value.connectionState == ConnectionState.Connected) {
                        chatGateway.sendMessage("Shared file via Supabase: $label", cfg.sessionId)
                    }
                    return@launch
                }

                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Не удалось прочитать файл")
                }
                if (bytes.size > MediaLimits.MAX_FILE_BYTES) {
                    error("Файл слишком большой (макс. ${MediaLimits.MAX_FILE_BYTES / 1024} КиБ)")
                }
                val kind = when {
                    resolvedMime.startsWith("image/") -> MediaKind.Photo
                    resolvedMime.startsWith("audio/") -> MediaKind.Voice
                    else -> MediaKind.File
                }
                val attachment = ChatAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = kind,
                    mime = resolvedMime,
                    name = name,
                    bytes = bytes
                )
                sendAttachments(listOf(attachment), caption = _uiState.value.draft.trim().ifBlank { null })
                _uiState.update { it.copy(draft = "") }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = e.message ?: "Ошибка файла") }
            }
        }
    }

    private fun sendAttachments(attachments: List<ChatAttachment>, caption: String?) {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            _uiState.update { it.copy(lastError = "Not connected") }
            return
        }
        val sessionId = _uiState.value.config.sessionId
        val envelope = MediaEnvelopeCodec.buildOutboundText(caption, attachments, sessionId)
        val wires = MediaEnvelopeCodec.wiresOf(attachments)
        val placeholder = buildString {
            if (!caption.isNullOrBlank()) append(caption).append('\n')
            attachments.forEach {
                append('[')
                append(it.kind.wire())
                append("] ")
                append(it.name)
                append('\n')
            }
        }.trim()
        val photoB64 = attachments.firstOrNull { it.kind == MediaKind.Photo }
            ?.let { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
        val first = attachments.first()
        beginSend(
            userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.User,
                content = placeholder,
                attachmentKind = first.kind,
                attachmentName = first.name,
                localMediaPath = first.localPath
            )
        ) {
            val cfg = _uiState.value.config
            withContext(Dispatchers.IO) {
                AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                    sessionId = sessionId,
                    senderName = AhccSupabaseClient.SENDER_ANDROID,
                    content = placeholder,
                    recipientName = AhccSupabaseClient.SENDER_HERMES,
                )
            }
            chatGateway.sendMedia(
                envelopeText = envelope,
                historyPlaceholder = placeholder,
                photoJpegBase64 = photoB64,
                attachmentWires = wires,
                sessionId = sessionId
            )
        }
    }

    private fun beginSend(userMessage: ChatMessage, block: suspend () -> Unit) {
        val assistantId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )
        _uiState.update {
            it.copy(
                isSending = true,
                messages = it.messages + userMessage + assistantPlaceholder
            )
        }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        lastError = e.message,
                        messages = state.messages.map { msg ->
                            if (msg.id == assistantId) {
                                msg.copy(
                                    content = "Failed to send: ${e.message}",
                                    isStreaming = false,
                                    role = MessageRole.System
                                )
                            } else msg
                        }
                    )
                }
            }
        }
    }

    fun playInbound(path: String) {
        voicePlayer.toggle(path) {
            _uiState.update { it.copy(playingPath = null) }
        }
        _uiState.update {
            it.copy(playingPath = if (voicePlayer.isPlaying) path else null)
        }
    }

    private fun handleIncoming(response: com.nous.ahcc.domain.model.HermesResponse) {
        when (response.event) {
            "history" -> {
                // Prefer Supabase history already loaded on connect.
                if (_uiState.value.messages.isNotEmpty()) return
                val loaded = response.history.orEmpty().mapNotNull { item ->
                    val role = when (item.role.lowercase()) {
                        "user" -> MessageRole.User
                        "assistant" -> MessageRole.Assistant
                        "system" -> MessageRole.System
                        else -> return@mapNotNull null
                    }
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = role,
                        content = item.content
                    )
                }
                _uiState.update { state ->
                    val note = response.content?.takeIf { it.isNotBlank() && loaded.isEmpty() }?.let {
                        listOf(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.System,
                                content = it
                            )
                        )
                    }.orEmpty()
                    state.copy(messages = loaded + note, isSending = false)
                }
            }
            "token", "message.delta" -> {
                val delta = response.delta ?: response.content.orEmpty()
                if (delta.isEmpty()) return
                appendToStreamingAssistant(delta)
            }
            "tool_call" -> {
                val name = response.name
                    ?: response.tool_call?.name
                    ?: "tool"
                val args = response.args ?: response.tool_call?.args
                val argsText = args?.entries?.joinToString { "${it.key}=${it.value}" }.orEmpty()
                val toolMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Tool,
                    content = if (argsText.isBlank()) "Calling $name…" else "Calling $name($argsText)",
                    toolName = name
                )
                _uiState.update { it.copy(messages = it.messages + toolMsg) }
            }
            "done", "message.complete" -> {
                finalizeStreamingAssistant(response.content)
                response.session_id?.let { sid ->
                    if (sid != _uiState.value.config.sessionId) {
                        updateConfig(_uiState.value.config.copy(sessionId = sid))
                    }
                }
            }
            "error" -> {
                finalizeStreamingAssistant(null)
                _uiState.update {
                    it.copy(
                        lastError = response.error ?: "Agent error",
                        messages = it.messages + ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.System,
                            content = response.error ?: "Agent error"
                        )
                    )
                }
            }
            "message" -> {
                val content = response.content
                    ?: response.data?.content
                    ?: response.delta
                    ?: return
                val hasStreaming = _uiState.value.messages.any { it.isStreaming }
                if (hasStreaming) {
                    replaceStreamingContent(content)
                    finalizeStreamingAssistant(content)
                } else {
                    val inbound = MediaEnvelopeCodec.parseInbound(
                        content,
                        getApplication<Application>().cacheDir
                    )
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.Assistant,
                                content = MediaEnvelopeCodec.stripMediaBlocksForDisplay(content),
                                inboundMedia = inbound,
                                localMediaPath = inbound.firstOrNull { m -> m.kind == MediaKind.Voice }?.filePath
                            )
                        )
                    }
                }
            }
            else -> {
                val delta = response.delta
                if (!delta.isNullOrEmpty()) appendToStreamingAssistant(delta)
            }
        }
    }

    private fun appendToStreamingAssistant(delta: String) {
        _uiState.update { state ->
            val msgs = state.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == MessageRole.Assistant && it.isStreaming }
            if (idx >= 0) {
                val current = msgs[idx]
                msgs[idx] = current.copy(content = current.content + delta)
            } else {
                msgs += ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = delta,
                    isStreaming = true
                )
            }
            state.copy(messages = msgs, isSending = true)
        }
    }

    private fun replaceStreamingContent(content: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.isStreaming && msg.role == MessageRole.Assistant) {
                        msg.copy(content = content)
                    } else msg
                }
            )
        }
    }

    private fun finalizeStreamingAssistant(fullContent: String?) {
        val cache = getApplication<Application>().cacheDir
        var hermesText = ""
        _uiState.update { state ->
            state.copy(
                isSending = false,
                messages = state.messages.map { msg ->
                    if (!msg.isStreaming) msg
                    else {
                        val raw = fullContent?.takeIf { it.isNotBlank() } ?: msg.content
                        hermesText = raw
                        val inbound = MediaEnvelopeCodec.parseInbound(raw, cache)
                        msg.copy(
                            isStreaming = false,
                            content = MediaEnvelopeCodec.stripMediaBlocksForDisplay(raw),
                            inboundMedia = inbound,
                            localMediaPath = inbound.firstOrNull { it.kind == MediaKind.Voice }?.filePath
                                ?: msg.localMediaPath
                        )
                    }
                }
            )
        }
        if (hermesText.isNotBlank()) {
            val cfg = _uiState.value.config
            viewModelScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                            sessionId = cfg.sessionId,
                            senderName = AhccSupabaseClient.SENDER_HERMES,
                            content = hermesText,
                            recipientName = AhccSupabaseClient.SENDER_ANDROID,
                        )
                    }
                }
            }
        }
    }

    fun clearChat() {
        chatGateway.clearHttpHistory()
        voicePlayer.stop()
        _uiState.update { it.copy(messages = emptyList(), lastError = null, playingPath = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    override fun onCleared() {
        voiceRecorder.cancel()
        voicePlayer.stop()
        super.onCleared()
    }
}

class HermesChatViewModelFactory(
    private val application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HermesChatViewModel::class.java)) {
            return HermesChatViewModel(application, preferences, chatGateway) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
