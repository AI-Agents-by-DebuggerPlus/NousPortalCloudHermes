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
import com.nous.ahcc.data.local.ChatToolLog
import com.nous.ahcc.data.local.ConnectionPreferences
import com.nous.ahcc.domain.model.AgentReplyParts
import com.nous.ahcc.domain.model.ChatAttachment
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MediaKind
import com.nous.ahcc.domain.model.MessageRole
import com.nous.ahcc.domain.model.TransportMode
import com.nous.ahcc.headset.HeadsetMonitorService
import com.nous.ahcc.media.ChatCuePlayer
import com.nous.ahcc.media.TtsVoiceChoice
import com.nous.ahcc.media.MediaEnvelopeCodec
import com.nous.ahcc.media.MediaLimits
import com.nous.ahcc.media.PhotoCompressor
import com.nous.ahcc.media.VoicePlayer
import com.nous.ahcc.media.VoiceRecorder
import com.nous.ahcc.data.supabase.AhccSupabaseClient
import com.nous.ahcc.service.HermesConnectionService
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val config: ConnectionConfig = ConnectionConfig(),
    val draft: String = "",
    val lastError: String? = null,
    /** One-shot UI notice (snackbar), e.g. “Аудиофайл отправлен”. */
    val statusNotice: String? = null,
    val isSending: Boolean = false,
    val isRecording: Boolean = false,
    val playingPath: String? = null,
    val headsetCaptureOn: Boolean = false,
    val toolStatus: String = "",
    val ttsVoices: List<TtsVoiceChoice> = emptyList(),
    val ttsVoicesStatus: String = "",
    val ttsEnglishVoice: String = "",
    val ttsRussianVoice: String = "",
)

class HermesChatViewModel(
    application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : AndroidViewModel(application) {

    private val app = application as AhccApp
    private val voiceRecorder = VoiceRecorder(application)
    private val voicePlayer = VoicePlayer()
    private val cues = ChatCuePlayer(application)
    private val toolLog = ChatToolLog(application)
    private val sendInFlight = AtomicBoolean(false)
    private val autoConnectStarted = AtomicBoolean(false)
    private var silenceWatchJob: Job? = null
    private var cueVoiceReply = false
    private var spokenReply: String? = null
    private var loggedTools: List<String> = emptyList()
    private var pendingAssistantId: String? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
                if (autoConnectStarted.compareAndSet(false, true)) {
                    connect()
                }
            }
        }
        viewModelScope.launch {
            preferences.ttsVoiceFlow.collect { saved ->
                _uiState.update {
                    it.copy(ttsEnglishVoice = saved.english, ttsRussianVoice = saved.russian)
                }
                cues.setPreferredVoices(saved.english, saved.russian)
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
        if (_uiState.value.connectionState != ConnectionState.Connected) return
        if (!label.equals("Play", ignoreCase = true) &&
            !label.equals("HeadsetHook", ignoreCase = true)
        ) {
            return
        }
        if (!app.headsetHub.tryConsumeVoiceGesture()) return
        onVoicePlayPressed()
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
        silenceWatchJob?.cancel()
        silenceWatchJob = null
        voiceRecorder.cancel()
        voicePlayer.stop()
        _uiState.update { it.copy(isRecording = false, playingPath = null) }
    }

    fun sendPrompt(userPrompt: String = _uiState.value.draft.trim()) {
        if (userPrompt.isBlank()) return
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
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
            val reply = app.telegramUser.sendTextAndWaitReply(
                text = userPrompt,
                botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME
            ) { partial -> showUserReply(partial, done = false) }
            showUserReply(reply)
        }
    }

    /** BT Play: start recording. A second Play stops and sends. */
    fun onVoicePlayPressed() {
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
            return
        }
        if (sendInFlight.get() || _uiState.value.isSending) {
            Log.i(TAG, "Play ignored — waiting for Hermes reply")
            _uiState.update { it.copy(statusNotice = "Ждём ответ агента…") }
            return
        }
        if (voiceRecorder.isRecording || _uiState.value.isRecording) {
            Log.i(TAG, "Play again → stop and send")
            stopVoiceAndSend(notice = "Аудиофайл отправлен")
            return
        }
        startVoiceRecordingWithSilenceStop()
    }

    /** Mic: start like Play; while recording, force-stop + send. */
    fun toggleVoiceRecording() {
        if (voiceRecorder.isRecording || _uiState.value.isRecording) {
            stopVoiceAndSend(notice = "Аудиофайл отправлен")
        } else {
            onVoicePlayPressed()
        }
    }

    private var recordToken = 0

    private fun startVoiceRecordingWithSilenceStop() {
        silenceWatchJob?.cancel()
        val token = ++recordToken
        cues.stopSpeaking()
        _uiState.update {
            it.copy(isRecording = true, lastError = null, statusNotice = "Запись… повторный Play отправит")
        }
        silenceWatchJob = viewModelScope.launch {
            cues.beepAwait()
            if (token != recordToken) return@launch
            runCatching {
                voiceRecorder.start()
                Log.i(TAG, "voice recording started")
            }.onFailure { e ->
                Log.e(TAG, "voice start failed: ${e.message}", e)
                _uiState.update { it.copy(isRecording = false, lastError = e.message ?: "Не удалось начать запись") }
                return@launch
            }
            delay(VoiceRecorder.MAX_RECORDING_MS)
            if (token == recordToken && voiceRecorder.isRecording) {
                Log.i(TAG, "voice max duration → auto-send")
                stopVoiceAndSend(notice = "Аудиофайл отправлен")
            }
        }
    }

    private fun stopVoiceAndSend(notice: String = "Аудиофайл отправлен") {
        recordToken++
        silenceWatchJob?.cancel()
        silenceWatchJob = null
        val wasRecording = voiceRecorder.isRecording
        if (sendInFlight.get()) {
            Log.w(TAG, "voice stop ignored, send already in flight")
            voiceRecorder.cancel()
            _uiState.update { it.copy(isRecording = false) }
            return
        }
        val result = if (wasRecording) voiceRecorder.stop() else null
        _uiState.update { it.copy(isRecording = false) }
        if (result == null) {
            if (wasRecording) {
                Log.w(TAG, "voice discarded (too short or failed)")
                _uiState.update { it.copy(lastError = "Запись слишком короткая") }
            }
            return
        }
        Log.i(
            TAG,
            "voice ready ${result.file.name} ${result.bytes.size}B ${result.durationMs}ms → Hermes"
        )
        val caption = _uiState.value.draft.trim().ifBlank {
            "Голосовое сообщение (${result.durationMs} ms)"
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
        sendAttachments(listOf(attachment), caption = caption)
        _uiState.update { it.copy(draft = "", statusNotice = notice) }
    }

    fun clearStatusNotice() {
        _uiState.update { it.copy(statusNotice = null) }
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
        Log.i(
            TAG,
            "sendAttachments kind=${first.kind} name=${first.name} raw=${first.bytes.size}B " +
                "wsCaptionChars=${placeholder.length} attachments=${wires.size} → Telegram"
        )
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
            if (first.kind == MediaKind.Voice && first.localPath != null) {
                cueVoiceReply = true
                cues.beep()
            }
            val cfg = _uiState.value.config
            withContext(Dispatchers.IO) {
                AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                    sessionId = sessionId,
                    senderName = AhccSupabaseClient.SENDER_ANDROID,
                    content = placeholder,
                    recipientName = AhccSupabaseClient.SENDER_HERMES,
                )
            }
            val reply = if (first.kind == MediaKind.Voice && first.localPath != null) {
                app.telegramUser.sendAudioAndWaitReply(
                    path = first.localPath,
                    durationMs = first.durationMs ?: 0L,
                    botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
                    caption = placeholder
                ) { partial -> showUserReply(partial, done = false) }
            } else {
                error("Отправка этого файла от пользователя пока только для голоса")
            }
            showUserReply(reply)
        }
    }

    private fun showUserReply(reply: String, done: Boolean = true) {
        val split = AgentReplyParts.split(reply)
        if (split.tools.isNotEmpty()) recordTools(split.tools)
        val answer = split.answer
        val targetId = pendingAssistantId
        _uiState.update { state ->
            val existing = state.messages.any { it.id == targetId }
            val updated = if (targetId != null && existing) {
                state.messages.map { msg ->
                    if (msg.id != targetId) msg
                    else if (answer.isNotEmpty()) msg.copy(content = answer, isStreaming = !done, role = MessageRole.Assistant)
                    else if (done) msg.copy(isStreaming = false)
                    else msg
                }
            } else if (answer.isNotEmpty()) {
                state.messages + ChatMessage(
                    id = targetId ?: UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = answer,
                    isStreaming = !done
                )
            } else {
                state.messages
            }
            state.copy(isSending = !done, messages = updated)
        }
        if (done) pendingAssistantId = null
        val speech = AgentReplyParts.forSpeech(answer)
        if (speech.isEmpty()) {
            if (done) {
                cueVoiceReply = false
                spokenReply = null
            }
            return
        }
        if (!done) {
            if (speech == spokenReply) return
            val firstAnswer = spokenReply == null
            spokenReply = speech
            if (firstAnswer && cueVoiceReply) cues.doubleBeepThenSpeak(speech) else cues.speak(speech)
            return
        }
        cueVoiceReply = false
        if (speech != spokenReply) cues.speak(speech)
        spokenReply = null
    }

    private fun recordTools(tools: List<String>) {
        val fresh = when {
            tools == loggedTools -> emptyList()
            tools.size > loggedTools.size && tools.subList(0, loggedTools.size) == loggedTools ->
                tools.subList(loggedTools.size, tools.size)
            else -> listOf(tools.last()).filter { it != loggedTools.lastOrNull() }
        }
        if (fresh.isNotEmpty()) {
            toolLog.append(fresh)
            loggedTools = if (tools.size >= loggedTools.size && tools.take(loggedTools.size) == loggedTools) {
                tools
            } else {
                loggedTools + fresh
            }
        }
        _uiState.update { it.copy(toolStatus = tools.last()) }
    }

    fun toolLogText(): String = toolLog.read()

    fun clearToolLog() {
        toolLog.clear()
        loggedTools = emptyList()
        _uiState.update { it.copy(toolStatus = "") }
    }

    fun refreshTtsVoices() {
        viewModelScope.launch {
            _uiState.update { it.copy(ttsVoicesStatus = "Загрузка голосов…") }
            var loaded = emptyList<TtsVoiceChoice>()
            repeat(8) { attempt ->
                loaded = withContext(Dispatchers.Default) { cues.listVoices() }
                if (loaded.isNotEmpty()) return@repeat
                delay(300L * (attempt + 1))
            }
            val status = if (loaded.isNotEmpty()) {
                "Google TTS: ${loaded.size} (en/ru)"
            } else {
                "Голоса en/ru не найдены. Установите голосовые данные Google TTS."
            }
            _uiState.update { it.copy(ttsVoices = loaded, ttsVoicesStatus = status) }
        }
    }

    fun selectTtsVoice(voice: TtsVoiceChoice) {
        val english = if (voice.language == "ru") _uiState.value.ttsEnglishVoice else voice.name
        val russian = if (voice.language == "ru") voice.name else _uiState.value.ttsRussianVoice
        cues.setPreferredVoices(english, russian)
        cues.preview(voice.name, voice.language)
        viewModelScope.launch { preferences.saveTtsVoices(english, russian) }
    }

    fun previewTtsVoice(voice: TtsVoiceChoice) {
        cues.preview(voice.name, voice.language)
    }

    private fun beginSend(userMessage: ChatMessage, block: suspend () -> Unit) {
        if (!sendInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "beginSend skipped — request already in flight")
            return
        }
        val assistantId = UUID.randomUUID().toString()
        spokenReply = null
        loggedTools = emptyList()
        pendingAssistantId = assistantId
        val assistantPlaceholder = ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )
        _uiState.update {
            it.copy(
                isSending = true,
                toolStatus = "",
                messages = it.messages + userMessage + assistantPlaceholder
            )
        }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                cueVoiceReply = false
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
            } finally {
                sendInFlight.set(false)
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
        silenceWatchJob?.cancel()
        voiceRecorder.cancel()
        voicePlayer.stop()
        cues.shutdown()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AHCC-ChatVM"
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
