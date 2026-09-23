package com.nous.ahcc.data

import com.nous.ahcc.data.http.HermesHttpSseClient
import com.nous.ahcc.data.websocket.HermesWebSocketManager
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.HermesResponse
import com.nous.ahcc.domain.model.TransportMode
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
 * Chat facade. Production path is Hermes Agent WebSocket (push frames only).
 * [HermesHttpSseClient] stays compiled as an unused legacy fallback; the UI cannot select it.
 */
class HermesChatGateway(
    private val webSocket: HermesWebSocketManager = HermesWebSocketManager(),
    private val httpSse: HermesHttpSseClient = HermesHttpSseClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJobs = mutableListOf<Job>()
    private var activeMode: TransportMode = TransportMode.WebSocket

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect(config: ConnectionConfig) {
        disconnect(clearHttpHistory = false)
        // User-facing path is always the agent WebSocket, even if old prefs said HTTP SSE.
        activeMode = TransportMode.WebSocket
        wireActiveTransport()
        webSocket.connect(config.copy(transport = TransportMode.WebSocket))
    }

    suspend fun sendMessage(text: String, sessionId: String? = null) {
        when (activeMode) {
            TransportMode.HttpSse -> httpSse.sendMessage(text, sessionId)
            TransportMode.WebSocket -> webSocket.sendMessage(text, sessionId)
        }
    }

    suspend fun sendMedia(
        envelopeText: String,
        historyPlaceholder: String,
        photoJpegBase64: String?,
        attachmentWires: List<com.nous.ahcc.domain.model.MediaAttachmentWire>?,
        sessionId: String? = null
    ) {
        when (activeMode) {
            TransportMode.HttpSse -> httpSse.sendMedia(
                envelopeText = envelopeText,
                historyPlaceholder = historyPlaceholder,
                photoJpegBase64 = photoJpegBase64,
                sessionId = sessionId
            )
            TransportMode.WebSocket -> webSocket.sendMediaMessage(
                // Short caption only. Binary payload is attachments[].data (ahcc.media.v1).
                text = historyPlaceholder,
                attachments = attachmentWires,
                sessionId = sessionId
            )
        }
    }

    fun disconnect(clearHttpHistory: Boolean = true) {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        webSocket.disconnect()
        if (clearHttpHistory) httpSse.clearHistory()
        httpSse.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearHttpHistory() {
        httpSse.clearHistory()
    }

    private fun wireActiveTransport() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        when (activeMode) {
            TransportMode.HttpSse -> {
                collectJobs += scope.launch {
                    httpSse.connectionState.collect { _connectionState.value = it }
                }
                collectJobs += scope.launch {
                    httpSse.lastError.collect { _lastError.value = it }
                }
                collectJobs += scope.launch {
                    httpSse.responses.collect { _responses.emit(it) }
                }
            }
            TransportMode.WebSocket -> {
                collectJobs += scope.launch {
                    webSocket.connectionState.collect { _connectionState.value = it }
                }
                collectJobs += scope.launch {
                    webSocket.lastError.collect { _lastError.value = it }
                }
                collectJobs += scope.launch {
                    webSocket.responses.collect { _responses.emit(it) }
                }
            }
        }
    }
}
