package com.nous.ahcc.data

import com.nous.ahcc.data.http.HermesHttpSseClient
import com.nous.ahcc.data.telegram.TelegramChatTransport
import com.nous.ahcc.data.websocket.HermesWebSocketManager
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.HermesResponse
import com.nous.ahcc.domain.model.MediaAttachmentWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat facade. The user-facing path is the Telegram Bot API.
 * WebSocket and HTTP SSE clients stay compiled and are not started from Connect.
 */
class HermesChatGateway(
    private val telegram: TelegramChatTransport = TelegramChatTransport(),
    private val webSocket: HermesWebSocketManager = HermesWebSocketManager(),
    private val httpSse: HermesHttpSseClient = HermesHttpSseClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJobs = mutableListOf<Job>()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect(config: ConnectionConfig) {
        disconnect(clearHttpHistory = false)
        wireTelegram()
        telegram.connect(config)
    }

    suspend fun sendMessage(text: String, sessionId: String? = null) {
        telegram.sendMessage(text)
    }

    suspend fun sendMedia(
        envelopeText: String,
        historyPlaceholder: String,
        photoJpegBase64: String?,
        attachmentWires: List<MediaAttachmentWire>?,
        sessionId: String? = null
    ) {
        val wire = attachmentWires?.firstOrNull()
        if (wire != null) {
            telegram.sendAttachment(historyPlaceholder, wire)
        } else if (historyPlaceholder.isNotBlank()) {
            telegram.sendMessage(historyPlaceholder)
        }
    }

    fun disconnect(clearHttpHistory: Boolean = true) {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        telegram.disconnect()
        webSocket.disconnect()
        if (clearHttpHistory) httpSse.clearHistory()
        httpSse.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearHttpHistory() {
        httpSse.clearHistory()
    }

    private fun wireTelegram() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        collectJobs += scope.launch {
            telegram.connectionState.collect { _connectionState.value = it }
        }
        collectJobs += scope.launch {
            telegram.lastError.collect { _lastError.value = it }
        }
        collectJobs += scope.launch {
            telegram.responses.collect { _responses.emit(it) }
        }
    }
}
