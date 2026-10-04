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
import com.nous.ahcc.domain.model.Addressee
import com.nous.ahcc.domain.model.AgentReplyParts
import com.nous.ahcc.domain.model.MemoryReviewNotification
import com.nous.ahcc.domain.model.MemoryReviewSummarizer
import com.nous.ahcc.domain.model.ChatAttachment
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MediaKind
import com.nous.ahcc.domain.model.MessageRole
import com.nous.ahcc.domain.model.TransportMode
import com.nous.ahcc.headset.HeadsetButtonEvent
import com.nous.ahcc.headset.HeadsetLinkAnnouncer
import com.nous.ahcc.headset.HeadsetMonitorService
import com.nous.ahcc.media.ChatCuePlayer
import com.nous.ahcc.media.SpeechTranscriber
import com.nous.ahcc.media.SpeechTranscript
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
import java.time.Duration
import java.time.OffsetDateTime
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
    val ttsFavoriteVoices: Set<String> = emptySet(),
    val localTranscription: Boolean = false,
    val flashcardsActive: Boolean = false,
    val flashcardCards: List<PhoneFlashcard> = emptyList(),
    val flashcardIndex: Int = 0,
    val flashcardBlanked: Boolean = true,
    val flashcardDisplay: com.nous.ahcc.domain.model.FlashcardDisplaySettings =
        com.nous.ahcc.domain.model.FlashcardDisplaySettings(),
    val playTapTestActive: Boolean = false,
    val currentAddressee: Addressee = Addressee.default,
    val defaultAddressee: Addressee = Addressee.default,
    val addressees: List<Addressee> = Addressee.defaultCatalog,
    /** TDLib user session (MTProto), required to send to the bot. */
    val telegramUserReady: Boolean = false,
    /** Full-screen memory / self-improvement approval notice from Hermes. */
    val memoryReviewOverlay: MemoryReviewOverlayState? = null,
    /** Auto-approve subsequent memory review notices; bot replies still appear in chat. */
    val memoryGlobalAutoApprove: Boolean = false,
)

data class PhoneFlashcard(val en: String, val ru: String)

class HermesChatViewModel(
    application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : AndroidViewModel(application) {

    private val app = application as AhccApp
    private val voiceRecorder = VoiceRecorder(application)
    private val voicePlayer = VoicePlayer()
    private val cues = ChatCuePlayer(application)
    private val transcriber = SpeechTranscriber(application)
    private val headsetAnnouncer = HeadsetLinkAnnouncer(application) { phrase -> cues.speak(phrase) }
    private val toolLog = ChatToolLog(application)
    private val sendInFlight = AtomicBoolean(false)
    private val autoConnectStarted = AtomicBoolean(false)
    private var silenceWatchJob: Job? = null
    private var cueVoiceReply = false
    private var spokenReply: String? = null
    private var loggedTools: List<String> = emptyList()
    private var pendingAssistantId: String? = null
    private val spokenFlashcards = LinkedHashSet<String>()
    private var addresseeSelectionInitialized = false
    private var lastMemoryOverlayKey: String? = null

    private fun watchFlashcardsForSpeech() {
        viewModelScope.launch {
            var primed = false
            while (true) {
                val cfg = _uiState.value.config
                if (cfg.supabaseUrl.isNotBlank() && cfg.supabaseAnonKey.isNotBlank()) {
                    val rows = runCatching {
                        withContext(Dispatchers.IO) {
                            val sb = AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey)
                            try {
                                sb.fetchRecentMessages(12)
                            } finally {
                                sb.close()
                            }
                        }
                    }.getOrElse { emptyList() }
                    val cards = rows.mapNotNull { row ->
                        if (!row.senderName.equals("Hermes", ignoreCase = true)) return@mapNotNull null
                        val card = AgentReplyParts.lastFlashcard(row.content) ?: return@mapNotNull null
                        row.createdAt.orEmpty() to card
                    }
                    if (!primed) {
                        primed = true
                        val newest = cards.maxByOrNull { it.first }
                        synchronized(spokenFlashcards) {
                            cards.forEach { spokenFlashcards.add(flashcardKey(it.second)) }
                        }
                        if (newest != null && isRecentFlashcard(newest.first)) {
                            synchronized(spokenFlashcards) {
                                spokenFlashcards.remove(flashcardKey(newest.second))
                            }
                            speakFlashcardOnce(newest.second)
                        }
                    } else {
                        cards.sortedBy { it.first }.forEach { speakFlashcardOnce(it.second) }
                    }
                }
                delay(4_000)
            }
        }
    }

    private fun onTelegramFlashcard(text: String) {
        maybeShowMemoryReviewOverlay(text)
        ingestFlashcardControl(text)
        val answer = AgentReplyParts.split(text).answer.ifBlank { text.trim() }
        if (answer.isBlank()) return
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = answer,
                )
            )
        }
        saveIncomingReply(answer)
        AgentReplyParts.lastFlashcard(answer)?.let { speakFlashcardOnce(it) }
    }

    private fun speakFlashcardOnce(card: AgentReplyParts.Flashcard): Boolean {
        val key = flashcardKey(card)
        synchronized(spokenFlashcards) {
            if (!spokenFlashcards.add(key)) return false
        }
        Log.i(TAG, "flashcard speak enChars=${card.en.length} ruChars=${card.ru.length}")
        rememberFlashcard(card)
        cues.speakFlashcard(
            card.en,
            card.ru,
            pauseMs = _uiState.value.flashcardDisplay.speechPauseSeconds * 1_000L,
        )
        return true
    }

    private fun ingestFlashcardControl(text: String) {
        val lower = text.lowercase()
        if (lower.contains("flashcard_stop")) {
            exitFlashcards(sendStop = false)
        } else if (lower.contains("flashcard_start")) {
            _uiState.update { it.copy(flashcardsActive = true, flashcardBlanked = true) }
        }
    }

    private fun rememberFlashcard(card: AgentReplyParts.Flashcard) {
        _uiState.update { state ->
            val line = PhoneFlashcard(card.en, card.ru)
            val duplicate = state.flashcardCards.any { it.en == line.en && it.ru == line.ru }
            val cards = if (duplicate) state.flashcardCards else state.flashcardCards + line
            state.copy(
                flashcardsActive = true,
                flashcardCards = cards,
                flashcardIndex = if (duplicate) state.flashcardIndex else cards.lastIndex.coerceAtLeast(0),
                flashcardBlanked = if (duplicate) state.flashcardBlanked else false,
            )
        }
    }

    private fun onFlashcardPlay(taps: Int) {
        when (taps) {
            1 -> revealFlashcard()
            2 -> {
                cues.speak("Next")
                blankFlashcards()
                val seen = _uiState.value.flashcardCards
                    .map { it.en.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val prompt = buildString {
                    append("next")
                    if (seen.isNotEmpty()) {
                        append("\nDo not repeat these English lemmas: ")
                        append(seen.joinToString(", "))
                    }
                }
                sendPrompt(prompt)
            }
            else -> {
                cues.speak("stop flashcards")
                exitFlashcards(sendStop = true, stopText = "stop_flashcards")
            }
        }
    }

    fun blankFlashcards() {
        _uiState.update { state ->
            if (state.flashcardsActive) state.copy(flashcardBlanked = true) else state
        }
    }

    fun revealFlashcard() {
        _uiState.update { state ->
            if (state.flashcardsActive) state.copy(flashcardBlanked = false) else state
        }
        replayCurrentFlashcardSpeech()
    }

    fun replayCurrentFlashcardSpeech() {
        val card = _uiState.value.flashcardCards.getOrNull(_uiState.value.flashcardIndex) ?: return
        val pauseMs = _uiState.value.flashcardDisplay.speechPauseSeconds * 1_000L
        cues.speakFlashcard(card.en, card.ru, pauseMs = pauseMs)
    }

    fun updateFlashcardDisplay(settings: com.nous.ahcc.domain.model.FlashcardDisplaySettings) {
        _uiState.update { it.copy(flashcardDisplay = settings) }
        viewModelScope.launch { preferences.saveFlashcardDisplay(settings) }
    }

    fun leaveFlashcards() {
        exitFlashcards(sendStop = false)
    }

    private fun exitFlashcards(sendStop: Boolean, stopText: String = "останови карточки") {
        val wasActive = _uiState.value.flashcardsActive
        _uiState.update { it.copy(flashcardsActive = false, flashcardBlanked = true) }
        if (sendStop && wasActive) {
            sendPrompt(stopText)
        }
    }

    fun enterPlayTapTest() {
        app.headsetHub.playTestActive = true
        app.headsetHub.btTestActive = false
        app.headsetHub.voiceGesturesEnabled = false
        abortVoiceForButtonTest("play tap test")
        _uiState.update { it.copy(playTapTestActive = true, isRecording = false) }
    }

    fun leavePlayTapTest() {
        app.headsetHub.playTestActive = false
        _uiState.update { it.copy(playTapTestActive = false) }
    }

    fun enterBluetoothTest() {
        app.headsetHub.btTestActive = true
        app.headsetHub.voiceGesturesEnabled = false
        abortVoiceForButtonTest("BT button test")
        _uiState.update { it.copy(isRecording = false) }
    }

    fun leaveBluetoothTest() {
        app.headsetHub.btTestActive = false
    }

    private fun abortVoiceForButtonTest(reason: String) {
        recordToken++
        silenceWatchJob?.cancel()
        silenceWatchJob = null
        if (voiceRecorder.isRecording) voiceRecorder.cancel()
        transcriber.cancel()
        Log.i(TAG, "voice aborted — $reason")
    }

    private fun isButtonTestScreen(): Boolean =
        app.headsetHub.suppressChatVoice || _uiState.value.playTapTestActive

    fun dismissMemoryReviewOverlay() {
        _uiState.update { it.copy(memoryReviewOverlay = null) }
    }

    fun memoryReviewApplyCurrent() = runMemorySlashWorkflow("/memory approve (one)") {
        memoryApproveOrRejectCurrent(approve = true)
    }

    fun memoryReviewDenyCurrent() = runMemorySlashWorkflow("/memory reject (one)") {
        _uiState.update { it.copy(memoryGlobalAutoApprove = false) }
        memoryApproveOrRejectCurrent(approve = false)
    }

    fun memoryReviewApplyAllSequential() = runMemorySlashWorkflow("/memory approve (each)") {
        memoryApproveOrRejectEach(approve = true, fromCurrent = true)
        dismissMemoryReviewOverlay()
    }

    fun memoryReviewDenyAllSequential() = runMemorySlashWorkflow("/memory reject all") {
        _uiState.update { it.copy(memoryGlobalAutoApprove = false) }
        memoryRejectAllPending()
        dismissMemoryReviewOverlay()
    }

    fun memoryReviewGlobalApplyAll() = runMemorySlashWorkflow("/memory global approve") {
        _uiState.update { it.copy(memoryGlobalAutoApprove = true) }
        memoryApproveOrRejectEach(approve = true, fromCurrent = true)
        dismissMemoryReviewOverlay()
    }

    private suspend fun memoryApproveOrRejectCurrent(approve: Boolean) {
        val id = resolveNextMemoryId() ?: run {
            showUserReply("No pending memory id for this notice.", done = true)
            dismissMemoryReviewOverlay()
            return
        }
        val cmd = if (approve) "/memory approve $id" else "/memory reject $id"
        val reply = app.telegramUser.sendTextAndWaitReply(
            text = cmd,
            botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
        ) { partial -> showUserReply(partial, done = false) }
        showUserReply(reply, done = true)
        advanceMemoryOverlayAfterAction()
    }

    private suspend fun memoryApproveOrRejectEach(approve: Boolean, fromCurrent: Boolean) {
        val ids = resolveRemainingMemoryIds(fromCurrent)
        memoryApproveOrRejectIds(ids, approve)
    }

    private suspend fun memoryRejectAllPending() {
        val reply = app.telegramUser.sendTextAndWaitReply(
            text = "/memory reject all",
            botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
        ) { partial -> showUserReply(partial, done = false) }
        showUserReply(reply, done = true)
    }

    private suspend fun memoryApproveOrRejectIds(ids: List<String>, approve: Boolean) {
        val validIds = ids.filter { MemoryReviewNotification.isValidPendingId(it) }.distinct()
        if (validIds.isEmpty()) {
            if (!approve) {
                memoryRejectAllPending()
                return
            }
            showUserReply("No pending memory writes.", done = true)
            return
        }
        validIds.forEachIndexed { index, id ->
            val cmd = if (approve) "/memory approve $id" else "/memory reject $id"
            val reply = app.telegramUser.sendTextAndWaitReply(
                text = cmd,
                botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
            ) { partial -> showUserReply(partial, done = false) }
            showUserReply(reply, done = index == validIds.lastIndex)
        }
    }

    private suspend fun resolveNextMemoryId(): String? =
        resolveRemainingMemoryIds(fromCurrent = true).firstOrNull()

    private suspend fun resolveRemainingMemoryIds(fromCurrent: Boolean): List<String> {
        val overlay = _uiState.value.memoryReviewOverlay
        if (overlay != null && overlay.items.isNotEmpty()) {
            val start = if (fromCurrent) overlay.currentIndex else 0
            return overlay.items.drop(start).map { it.id }
        }
        val pending = app.telegramUser.sendTextAndWaitReply(
            text = "/memory pending",
            botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
        ) { }
        return MemoryReviewNotification.parsePendingIds(pending)
    }

    private fun advanceMemoryOverlayAfterAction() {
        _uiState.update { state ->
            val overlay = state.memoryReviewOverlay ?: return@update state.copy(memoryReviewOverlay = null)
            if (overlay.items.isEmpty()) {
                return@update state.copy(memoryReviewOverlay = null)
            }
            val nextIndex = overlay.currentIndex + 1
            if (nextIndex >= overlay.items.size) {
                state.copy(memoryReviewOverlay = null)
            } else {
                state.copy(memoryReviewOverlay = overlay.copy(currentIndex = nextIndex))
            }
        }
    }

    private fun onMemoryReviewPush(text: String) {
        val answer = AgentReplyParts.split(text).answer.ifBlank { text.trim() }
        if (answer.isNotBlank()) {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.Assistant,
                        content = memoryDisplayAnswer(answer),
                    )
                )
            }
            saveIncomingReply(answer)
        }
        if (_uiState.value.memoryGlobalAutoApprove) {
            val ids = MemoryReviewNotification.parsePendingItems(answer).map { it.id }
                .ifEmpty {
                    MemoryReviewNotification.parsePendingIds(answer)
                }
            if (ids.isNotEmpty()) {
                runMemorySlashWorkflow("Global auto-approve memory") {
                    memoryApproveOrRejectIds(ids, approve = true)
                }
            }
            return
        }
        maybeShowMemoryReviewOverlay(text)
    }

    private fun memoryDisplayAnswer(answer: String): String {
        if (MemoryReviewNotification.isNotification(answer)) {
            return MemoryReviewSummarizer.summarizeBody(answer)
        }
        MemoryReviewSummarizer.summarizeMemoryReply(answer)?.let { return it }
        return answer
    }

    private fun maybeShowMemoryReviewOverlay(raw: String) {
        val body = AgentReplyParts.split(raw).answer.ifBlank { raw.trim() }
        if (!MemoryReviewNotification.isNotification(body)) return
        val key = body
        if (key == lastMemoryOverlayKey && _uiState.value.memoryReviewOverlay != null) return
        lastMemoryOverlayKey = key
        val items = MemoryReviewNotification.parsePendingItems(body)
        _uiState.update {
            it.copy(
                memoryReviewOverlay = MemoryReviewOverlayState(
                    body = body,
                    items = items,
                ),
            )
        }
    }

    private fun runMemorySlashWorkflow(label: String, block: suspend () -> Unit) {
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
            return
        }
        if (sendInFlight.get() || _uiState.value.isSending) {
            _uiState.update { it.copy(statusNotice = "Дождитесь ответа Hermes, затем повторите") }
            return
        }
        beginSend(
            userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.User,
                content = label,
            )
        ) {
            block()
        }
    }

    private fun flashcardKey(card: AgentReplyParts.Flashcard) = "${card.en}\n${card.ru}"

    private fun isRecentFlashcard(createdAt: String): Boolean {
        val at = runCatching { OffsetDateTime.parse(createdAt).toInstant() }.getOrNull() ?: return false
        return Duration.between(at, java.time.Instant.now()).toMinutes() <= 45
    }

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
            preferences.ttsFavoritesFlow.collect { names ->
                _uiState.update { it.copy(ttsFavoriteVoices = names) }
            }
        }
        headsetAnnouncer.start()
        app.telegramUser.onIdleMessage = { text -> onTelegramFlashcard(text) }
        app.telegramUser.onMemoryReviewMessage = { text -> onMemoryReviewPush(text) }
        watchFlashcardsForSpeech()
        viewModelScope.launch {
            preferences.localTranscriptionFlow.collect { enabled ->
                _uiState.update { it.copy(localTranscription = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.flashcardDisplayFlow.collect { display ->
                _uiState.update { it.copy(flashcardDisplay = display) }
            }
        }
        viewModelScope.launch {
            preferences.addresseeSettingsFlow.collect { settings ->
                val default = Addressee.resolve(settings.catalog, settings.defaultAddresseeId)
                _uiState.update { state ->
                    val current = if (!addresseeSelectionInitialized) {
                        addresseeSelectionInitialized = true
                        default
                    } else {
                        settings.catalog.find { it.id == state.currentAddressee.id } ?: default
                    }
                    state.copy(
                        addressees = settings.catalog,
                        defaultAddressee = default,
                        currentAddressee = current,
                    )
                }
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
            app.telegramUser.auth.collect { auth ->
                _uiState.update {
                    it.copy(telegramUserReady = auth is com.nous.ahcc.data.telegram.TelegramUserAuth.Ready)
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
                onHeadsetEvent(event)
            }
        }
    }

    fun claimHeadsetButtons() {
        HeadsetMonitorService.reassert(getApplication())
        if (app.headsetHub.playTestActive || app.headsetHub.btTestActive) {
            app.headsetHub.voiceGesturesEnabled = false
            return
        }
        app.headsetHub.voiceGesturesEnabled = true
    }

    private fun ensureHeadsetCapture() {
        claimHeadsetButtons()
    }

    private fun onHeadsetEvent(event: HeadsetButtonEvent) {
        if (isButtonTestScreen()) return
        val play = event.tapCount > 1 ||
            event.label.equals("Play", ignoreCase = true) ||
            event.label.equals("HeadsetHook", ignoreCase = true) ||
            event.label.equals("DoublePlay", ignoreCase = true) ||
            event.label.equals("TriplePlay", ignoreCase = true)
        if (!play) return
        if (_uiState.value.flashcardsActive) {
            onFlashcardPlay(event.tapCount.coerceIn(1, 3))
            return
        }
        if (event.tapCount != 1) return
        if (_uiState.value.connectionState != ConnectionState.Connected) return
        if (!app.headsetHub.tryConsumeVoiceGesture(minIntervalMs = 40L)) return
        onVoicePlayPressed()
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun onAddresseeSelected(addressee: Addressee) {
        _uiState.update { it.copy(currentAddressee = addressee) }
    }

    fun setDefaultAddressee(addressee: Addressee) {
        viewModelScope.launch {
            preferences.setDefaultAddresseeId(addressee.id)
            _uiState.update {
                it.copy(
                    defaultAddressee = addressee,
                    statusNotice = "${addressee.displayName} is default",
                )
            }
        }
    }

    fun deleteCurrentAddressee() {
        val current = _uiState.value.currentAddressee
        if (current.id == Addressee.LIAISON_ID) {
            _uiState.update { it.copy(statusNotice = "MainAgent cannot be deleted") }
            return
        }
        viewModelScope.launch {
            val next = preferences.removeAddressee(current.id)
            val fallback = _uiState.value.defaultAddressee
            _uiState.update {
                it.copy(
                    addressees = next,
                    currentAddressee = Addressee.resolve(next, fallback.id),
                    statusNotice = "Receiver deleted",
                )
            }
        }
    }

    fun addAddressee(displayName: String, addressingKey: String?) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val existing = preferences.readAddresseeCatalog()
            val id = addressingKey?.trim()?.takeIf { it.isNotEmpty() }
                ?: Addressee.newId(name, existing)
            if (existing.any { it.id.equals(id, ignoreCase = true) }) {
                _uiState.update { it.copy(lastError = "Addressing key already exists: $id") }
                return@launch
            }
            val added = Addressee(id, name)
            val next = preferences.addAddressee(added)
            _uiState.update {
                it.copy(addressees = next, currentAddressee = added, statusNotice = "Receiver added")
            }
        }
    }

    private fun wireForAgent(text: String): String {
        val body = text.trim()
        // Slash memory/session commands must go to Hermes bot as-is (no TO: routing).
        if (body.startsWith("/memory", ignoreCase = true) ||
            body.startsWith("/new", ignoreCase = true)
        ) {
            return body
        }
        return Addressee.wireText(body, _uiState.value.currentAddressee)
    }

    private fun maybeResetAddresseeAfterSend(userText: String) {
        if (Addressee.isSessionEndCommand(userText)) {
            val default = _uiState.value.defaultAddressee
            _uiState.update { it.copy(currentAddressee = default) }
        }
    }

    fun updateConfig(config: ConnectionConfig) {
        viewModelScope.launch {
            preferences.save(config)
        }
    }

    fun connect(forceNewSession: Boolean = false) {
        val config = _uiState.value.config
        viewModelScope.launch {
            sendInFlight.set(false)
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
        if (isButtonTestScreen()) {
            Log.i(TAG, "send blocked — button test screen")
            _uiState.update { it.copy(statusNotice = "Отправка отключена на экране теста кнопок") }
            return
        }
        if (userPrompt.isBlank()) return
        if (isNewSessionCommand(userPrompt)) {
            startHermesSession()
            return
        }
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
            return
        }
        if (sendInFlight.get() || _uiState.value.isSending) {
            Log.i(TAG, "send blocked — waiting for Hermes reply")
            _uiState.update { it.copy(statusNotice = "Ждём ответ Hermes… (до 3 мин)") }
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
            val wireText = wireForAgent(userPrompt)
            withContext(Dispatchers.IO) {
                AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                    sessionId = cfg.sessionId,
                    senderName = AhccSupabaseClient.SENDER_ANDROID,
                    content = wireText,
                    recipientName = AhccSupabaseClient.SENDER_HERMES,
                )
            }
            val reply = app.telegramUser.sendTextAndWaitReply(
                text = wireText,
                botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME
            ) { partial -> showUserReply(partial, done = false) }
            showUserReply(reply)
            maybeResetAddresseeAfterSend(userPrompt)
        }
    }

    /** BT Play: start recording. A second Play stops and sends. */
    fun onVoicePlayPressed() {
        if (isButtonTestScreen()) {
            Log.i(TAG, "Play ignored — button test screen")
            return
        }
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
            return
        }
        if (sendInFlight.get() || _uiState.value.isSending) {
            Log.i(TAG, "Play ignored — waiting for Hermes reply")
            _uiState.update { it.copy(statusNotice = "Ждём ответ агента…") }
            return
        }
        if (_uiState.value.localTranscription) {
            if (_uiState.value.isRecording) {
                transcriber.cancel()
                _uiState.update { it.copy(isRecording = false, statusNotice = "Распознавание отменено") }
            } else {
                startLocalTranscription()
            }
            return
        }
        if (voiceRecorder.isRecording || _uiState.value.isRecording) {
            Log.i(TAG, "Play again → stop and send")
            stopVoiceAndSend(notice = "Аудиофайл отправлен")
            return
        }
        startVoiceRecordingWithSilenceStop()
    }

    private fun startLocalTranscription() {
        cues.stopSpeaking()
        _uiState.update {
            it.copy(isRecording = true, lastError = null, statusNotice = "Слушаю… распознавание на телефоне")
        }
        viewModelScope.launch {
            cues.beepAwait()
            if (!_uiState.value.isRecording) return@launch
            transcriber.start("ru-RU") { result ->
                _uiState.update { it.copy(isRecording = false) }
                when (result) {
                    is SpeechTranscript.Success -> {
                        if (isVoiceModeCommand(result.text)) {
                            setLocalTranscription(false)
                            cues.speak("Switched to voice messages.")
                            _uiState.update { it.copy(statusNotice = "Режим голосовых сообщений") }
                        } else {
                            sendPrompt(result.text)
                        }
                    }
                    SpeechTranscript.Empty ->
                        _uiState.update { it.copy(lastError = "Речь не распознана") }
                    SpeechTranscript.Cancelled ->
                        _uiState.update { it.copy(statusNotice = "Распознавание отменено") }
                    is SpeechTranscript.Error ->
                        _uiState.update { it.copy(lastError = result.message) }
                }
            }
        }
    }

    /** Mic: start like Play; while recording, force-stop + send. */
    fun toggleVoiceRecording() {
        onVoicePlayPressed()
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
        if (isButtonTestScreen()) {
            recordToken++
            silenceWatchJob?.cancel()
            silenceWatchJob = null
            voiceRecorder.cancel()
            _uiState.update { it.copy(isRecording = false) }
            Log.i(TAG, "voice send blocked — button test screen")
            return
        }
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
        val wirePlaceholder = wireForAgent(placeholder)
        val captionForEndCheck = caption?.trim().orEmpty().ifBlank { placeholder }
        val photoB64 = attachments.firstOrNull { it.kind == MediaKind.Photo }
            ?.let { Base64.encodeToString(it.bytes, Base64.NO_WRAP) }
        val first = attachments.first()
        Log.i(
            TAG,
            "sendAttachments kind=${first.kind} name=${first.name} raw=${first.bytes.size}B " +
                "wsCaptionChars=${wirePlaceholder.length} attachments=${wires.size} → Telegram"
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
                    content = wirePlaceholder,
                    recipientName = AhccSupabaseClient.SENDER_HERMES,
                )
            }
            val reply = if (first.kind == MediaKind.Voice && first.localPath != null) {
                app.telegramUser.sendAudioAndWaitReply(
                    path = first.localPath,
                    durationMs = first.durationMs ?: 0L,
                    botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME,
                    caption = "$wirePlaceholder\n\n$VOICE_FAIL_INSTRUCTION"
                ) { partial -> showUserReply(partial, done = false) }
            } else {
                error("Отправка этого файла от пользователя пока только для голоса")
            }
            showUserReply(reply)
            maybeResetAddresseeAfterSend(captionForEndCheck)
        }
    }

    private fun isVoiceModeCommand(text: String): Boolean {
        val normalized = text.trim().lowercase().trim { it in ".,!?;:\"'«»" }
        return normalized == "voice mode" || normalized == "режим голосовых сообщений"
    }

    private fun isNewSessionCommand(text: String): Boolean {
        val normalized = text.trim().lowercase().trim { it in ".,!?;:\"'«»" }
        return normalized == "new session" || normalized == "новая сессия"
    }

    /** Slash command on the Hermes gateway. Does not enter the model context. */
    private fun startHermesSession() {
        if (!app.telegramUser.isReady) {
            _uiState.update { it.copy(lastError = "Войдите в Telegram как пользователь: Настройки") }
            return
        }
        val cfg = _uiState.value.config
        viewModelScope.launch {
            val resolved = try {
                withContext(Dispatchers.IO) {
                    AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).resolveOrCreateSession(
                        forceNew = true,
                        preferredId = cfg.sessionId,
                        senderName = AhccSupabaseClient.SENDER_ANDROID,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(lastError = e.message ?: "Не удалось создать сессию") }
                return@launch
            }
            val next = cfg.copy(sessionId = resolved)
            preferences.save(next)
            _uiState.update {
                it.copy(
                    config = next,
                    draft = "",
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.System,
                            content = "Session $resolved"
                        )
                    )
                )
            }
            beginSend(
                userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.User,
                    content = "/new now"
                )
            ) {
                val reply = app.telegramUser.sendTextAndWaitReply(
                    text = "/new now",
                    botUsername = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_USERNAME
                ) { partial -> showUserReply(partial, done = false) }
                showUserReply(reply)
                if (reply.isBlank()) cues.speak("New session started.")
            }
        }
    }

    private fun showUserReply(reply: String, done: Boolean = true) {
        val split = AgentReplyParts.split(reply)
        if (split.tools.isNotEmpty()) recordTools(split.tools)
        val answer = split.answer.ifBlank { reply.trim() }
        val isMemory = MemoryReviewNotification.isNotification(reply) ||
            MemoryReviewNotification.isNotification(answer)
        val displayAnswer = memoryDisplayAnswer(answer)
        // Don't TTS raw pending dumps; show RU summary in the bubble instead.
        val speech = if (isMemory) "" else AgentReplyParts.forSpeech(answer)
        val typing = !done && speech.isEmpty() && !isMemory
        val targetId = pendingAssistantId
        _uiState.update { state ->
            val existing = state.messages.any { it.id == targetId }
            val updated = if (targetId != null && existing) {
                state.messages.map { msg ->
                    if (msg.id != targetId) msg
                    else if (displayAnswer.isNotEmpty()) {
                        msg.copy(content = displayAnswer, isStreaming = typing, role = MessageRole.Assistant)
                    } else if (!typing) msg.copy(isStreaming = false)
                    else msg
                }
            } else if (displayAnswer.isNotEmpty()) {
                state.messages + ChatMessage(
                    id = targetId ?: UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = displayAnswer,
                    isStreaming = typing
                )
            } else {
                state.messages
            }
            state.copy(isSending = if (done || isMemory) false else typing, messages = updated)
        }
        if (AgentReplyParts.requestsLocalTranscription(answer)) {
            if (!done) return
            pendingAssistantId = null
            saveIncomingReply(answer)
            setLocalTranscription(true)
            cueVoiceReply = false
            spokenReply = null
            cues.stopWorkingCue()
            cues.speak("Switched to phone transcription.")
            return
        }
        if (done) {
            pendingAssistantId = null
            saveIncomingReply(answer)
            maybeShowMemoryReviewOverlay(answer)
            ingestFlashcardControl(answer)
            val card = AgentReplyParts.lastFlashcard(answer)
            if (card != null) {
                cueVoiceReply = false
                spokenReply = null
                cues.stopWorkingCue()
                speakFlashcardOnce(card)
                return
            }
        }
        if (speech.isEmpty()) {
            if (done) {
                cueVoiceReply = false
                spokenReply = null
                cues.stopWorkingCue()
            }
            return
        }
        cues.stopWorkingCue()
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

    fun toggleTtsFavorite(voice: TtsVoiceChoice) {
        val current = _uiState.value.ttsFavoriteVoices
        val next = if (voice.name in current) current - voice.name else current + voice.name
        _uiState.update { it.copy(ttsFavoriteVoices = next) }
        viewModelScope.launch { preferences.saveTtsFavorites(next) }
    }

    fun previewTtsVoice(voice: TtsVoiceChoice) {
        cues.preview(voice.name, voice.language)
    }

    private fun beginSend(userMessage: ChatMessage, block: suspend () -> Unit) {
        if (!sendInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "beginSend skipped — request already in flight")
            _uiState.update { it.copy(statusNotice = "Уже отправляется предыдущее сообщение…") }
            return
        }
        val assistantId = UUID.randomUUID().toString()
        spokenReply = null
        loggedTools = emptyList()
        pendingAssistantId = assistantId
        cues.startWorkingCue()
        val assistantPlaceholder = ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )
        _uiState.update {
            it.copy(
                draft = if (userMessage.role == MessageRole.User) "" else it.draft,
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
                cues.stopWorkingCue()
                sendInFlight.set(false)
                _uiState.update { state ->
                    if (state.isSending) state.copy(isSending = false) else state
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
        saveIncomingReply(hermesText)
    }

    private fun saveIncomingReply(content: String) {
        val text = content.trim()
        if (text.isBlank()) return
        val cfg = _uiState.value.config
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey).insert(
                        sessionId = cfg.sessionId,
                        senderName = AhccSupabaseClient.SENDER_HERMES,
                        content = text,
                        recipientName = AhccSupabaseClient.SENDER_ANDROID,
                    )
                }
            }
        }
    }

    fun clearChat() {
        chatGateway.clearHttpHistory()
        voicePlayer.stop()
        synchronized(spokenFlashcards) { spokenFlashcards.clear() }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                lastError = null,
                playingPath = null,
                statusNotice = "Очистка чата и Supabase…",
                flashcardCards = emptyList(),
                flashcardIndex = 0,
                flashcardsActive = false,
            )
        }
        viewModelScope.launch {
            val cfg = _uiState.value.config
            if (cfg.supabaseUrl.isBlank() || cfg.supabaseAnonKey.isBlank()) {
                _uiState.update {
                    it.copy(statusNotice = "Локальный чат очищен (Supabase не настроен)")
                }
                return@launch
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val sb = AhccSupabaseClient(cfg.supabaseUrl, cfg.supabaseAnonKey)
                    try {
                        sb.clearAllAhccData()
                    } finally {
                        sb.close()
                    }
                }
            }
            result.onSuccess {
                Log.i(TAG, "clearChat: Supabase wiped")
            }.onFailure { e ->
                Log.e(TAG, "clearChat: Supabase wipe failed: ${e.message}", e)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(statusNotice = "Чат и Supabase (ahcc_messages + ahcc_files) очищены")
                    },
                    onFailure = { e ->
                        state.copy(
                            lastError = "Локальный чат очищен, Supabase: ${e.message}",
                            statusNotice = "Локальный чат очищен; ошибка удаления в Supabase",
                        )
                    },
                )
            }
        }
    }

    fun setLocalTranscription(enabled: Boolean) {
        _uiState.update { it.copy(localTranscription = enabled) }
        viewModelScope.launch { preferences.saveLocalTranscription(enabled) }
    }

    fun shutdownApp(finish: () -> Unit) {
        cues.stopWorkingCue()
        transcriber.cancel()
        voiceRecorder.cancel()
        viewModelScope.launch {
            runCatching { cues.speakAwait("AHCC is closing") }
            finish()
        }
    }

    fun stopSpokenReply() {
        cues.stopSpeaking()
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }

    override fun onCleared() {
        silenceWatchJob?.cancel()
        headsetAnnouncer.stop()
        transcriber.shutdown()
        voiceRecorder.cancel()
        voicePlayer.stop()
        cues.shutdown()
        super.onCleared()
    }

    companion object {
        private const val TAG = "AHCC-ChatVM"
        private const val VOICE_FAIL_INSTRUCTION =
            "If you cannot understand or transcribe the speech, reply with only this JSON and no other text: " +
                "{\"skill\":\"local_transcription\",\"enabled\":true}"
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
