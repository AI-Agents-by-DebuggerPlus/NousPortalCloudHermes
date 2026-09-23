package com.nous.ahcc.data.websocket

import android.util.Log
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.HermesMessageData
import com.nous.ahcc.domain.model.HermesRequest
import com.nous.ahcc.domain.model.HermesResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Singleton-style WebSocket manager for Hermes Agent.
 * Path: /v1/ws/chat?session_id=… with Authorization: Bearer &lt;apiKey&gt;.
 */
class HermesWebSocketManager {

    companion object {
        private const val TAG = "AHCC-WS"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null
    private var intentionalDisconnect = false
    private var currentConfig: ConnectionConfig? = null

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    /** Stream of parsed Hermes events for the ViewModel. */
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect(config: ConnectionConfig) {
        intentionalDisconnect = false
        currentConfig = config
        val scheme = if (config.useTls) "wss" else "ws"
        Log.i(
            TAG,
            "connect requested $scheme://${config.host}:${config.port}/v1/ws/chat?session_id=${config.sessionId} keyLen=${config.apiKey.length}"
        )
        connectJob?.cancel()
        connectJob = scope.launch {
            connectWithRetry(config)
        }
    }

    /** Exponential backoff reconnect on failures (max 30s), per Gemini docs. */
    private suspend fun connectWithRetry(config: ConnectionConfig) {
        var delayMs = 1_000L
        while (scope.isActive && !intentionalDisconnect) {
            try {
                _connectionState.value = ConnectionState.Connecting
                Log.i(TAG, "opening session (backoff=${delayMs}ms)")
                openSession(config)
                // Session closed cleanly — reset backoff and loop unless intentional
                Log.w(TAG, "session ended")
                delayMs = 1_000L
                if (intentionalDisconnect) break
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e::class.java.simpleName}: ${e.message}", e)
                _lastError.value = e.message ?: e::class.java.simpleName
                _connectionState.value = ConnectionState.Error
                if (intentionalDisconnect) break
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        if (intentionalDisconnect) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private suspend fun openSession(config: ConnectionConfig) {
        ensureClient()
        val httpClient = client ?: error("HttpClient not initialized")

        httpClient.webSocket(
            request = {
                url {
                    protocol = if (config.useTls) {
                        io.ktor.http.URLProtocol.WSS
                    } else {
                        io.ktor.http.URLProtocol.WS
                    }
                    host = config.host
                    port = config.port
                    path("/v1/ws/chat")
                    parameters.append("session_id", config.sessionId)
                }
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
        ) {
            session = this
            _connectionState.value = ConnectionState.Connected
            _lastError.value = null
            Log.i(TAG, "CONNECTED")

            // Read WebSocket frames and publish as HermesResponse
            for (frame in this.incoming) {
                val textFrame = frame as? Frame.Text ?: continue
                val text = textFrame.readText()
                Log.d(TAG, "<- frame(${text.length}): ${text.take(240)}")
                parseIncoming(text)?.let { _responses.emit(it) }
            }
        }
        session = null
    }

    private fun parseIncoming(textJson: String): HermesResponse? {
        return try {
            json.decodeFromString(HermesResponse.serializer(), textJson)
        } catch (_: Exception) {
            // Fallback: treat raw text as a token delta so chat still works
            HermesResponse(event = "token", delta = textJson)
        }
    }

    suspend fun sendMessage(text: String, sessionId: String? = currentConfig?.sessionId) {
        sendMediaMessage(text = text, attachments = null, sessionId = sessionId)
    }

    suspend fun sendMediaMessage(
        text: String,
        attachments: List<com.nous.ahcc.domain.model.MediaAttachmentWire>?,
        sessionId: String? = currentConfig?.sessionId
    ) {
        val payload = HermesRequest(
            event = "user_message",
            session_id = sessionId,
            content = text,
            data = HermesMessageData(role = "user", content = text),
            attachments = attachments
        )
        val encoded = json.encodeToString(HermesRequest.serializer(), payload)
        val attachSummary = attachments.orEmpty().joinToString { "${it.kind}:${it.size}B" }
        Log.i(
            TAG,
            "-> send chars=${encoded.length} attachments=[${attachSummary.ifBlank { "none" }}] " +
                "content=${text.take(120)}"
        )
        sendMutex.withLock {
            val active = session ?: error("WebSocket is not connected")
            active.send(Frame.Text(encoded))
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect requested")
        intentionalDisconnect = true
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            try {
                session?.close()
            } catch (_: Exception) {
            }
            session = null
            client?.close()
            client = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun ensureClient() {
        if (client != null) return
        client = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = 20_000
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }
}
