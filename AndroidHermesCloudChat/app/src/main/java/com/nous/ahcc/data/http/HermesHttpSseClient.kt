package com.nous.ahcc.data.http

import android.util.Log
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.HermesResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible chat via Nous Inference (`/v1/chat/completions` SSE).
 * Emits the same [HermesResponse] events as the WebSocket path (token / done / error).
 */
class HermesHttpSseClient {

    companion object {
        private const val TAG = "AHCC-HTTP"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()

    private var client: HttpClient? = null
    private var currentConfig: ConnectionConfig? = null
    private var streamJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val history = mutableListOf<ChatTurn>()
    private var agentApiBase: String? = null

    fun connect(config: ConnectionConfig) {
        currentConfig = config
        agentApiBase = null
        history.clear()
        ensureClient()
        scope.launch {
            _connectionState.value = ConnectionState.Connecting
            try {
                val base = config.inferenceBaseUrl.trimEnd('/')
                val response = client!!.get("$base/v1/models") {
                    if (config.apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    }
                }
                if (!response.status.isSuccess()) {
                    error("Inference auth failed: HTTP ${response.status.value}")
                }

                // Server session history is intentionally not loaded — current session only.
                history.clear()
                agentApiBase = null

                _connectionState.value = ConnectionState.Connected
                _lastError.value = null
                Log.i(TAG, "CONNECTED via HTTP SSE base=$base model=${config.model}")
            } catch (e: Exception) {
                Log.e(TAG, "connect failed: ${e.message}", e)
                _lastError.value = e.message ?: e::class.java.simpleName
                _connectionState.value = ConnectionState.Error
            }
        }
    }

    suspend fun sendMessage(text: String, sessionId: String? = currentConfig?.sessionId) {
        sendUserContent(
            historyText = text,
            requestContent = TextRequestContent.Plain(text),
            sessionId = sessionId
        )
    }

    /**
     * Send media via AHCC_MEDIA_V1 text envelope; photos also as OpenAI image_url when possible.
     */
    suspend fun sendMedia(
        envelopeText: String,
        historyPlaceholder: String,
        photoJpegBase64: String?,
        sessionId: String? = currentConfig?.sessionId
    ) {
        val requestContent = if (photoJpegBase64 != null) {
            TextRequestContent.Multimodal(
                text = envelopeText,
                jpegBase64 = photoJpegBase64
            )
        } else {
            TextRequestContent.Plain(envelopeText)
        }
        sendUserContent(historyPlaceholder, requestContent, sessionId)
    }

    private suspend fun sendUserContent(
        historyText: String,
        requestContent: TextRequestContent,
        sessionId: String?
    ) {
        val config = currentConfig ?: error("Not connected")
        if (_connectionState.value != ConnectionState.Connected) {
            error("HTTP transport is not connected")
        }
        sendMutex.withLock {
            // Do not cancel an in-flight completion — wait, then send next.
            streamJob?.join()
            streamJob = scope.launch {
                streamCompletion(config, historyText, requestContent, sessionId)
            }
            streamJob?.join()
        }
    }

    private sealed class TextRequestContent {
        data class Plain(val text: String) : TextRequestContent()
        data class Multimodal(val text: String, val jpegBase64: String) : TextRequestContent()
    }

    private suspend fun streamCompletion(
        config: ConnectionConfig,
        historyText: String,
        requestContent: TextRequestContent,
        sessionId: String?
    ) {
        history += ChatTurn(role = "user", content = historyText)
        val base = config.inferenceBaseUrl.trimEnd('/')

        suspend fun postOnce(useMultimodal: Boolean): Boolean {
            val body = buildJsonObject {
                put("model", config.model)
                put("stream", true)
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "disabled")
                    }
                )
                put(
                    "messages",
                    buildJsonArray {
                        history.dropLast(1).forEach { turn ->
                            add(
                                buildJsonObject {
                                    put("role", turn.role)
                                    put("content", turn.content)
                                }
                            )
                        }
                        add(
                            buildJsonObject {
                                put("role", "user")
                                when {
                                    useMultimodal && requestContent is TextRequestContent.Multimodal -> {
                                        put(
                                            "content",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "text")
                                                        put("text", requestContent.text)
                                                    }
                                                )
                                                add(
                                                    buildJsonObject {
                                                        put("type", "image_url")
                                                        put(
                                                            "image_url",
                                                            buildJsonObject {
                                                                put(
                                                                    "url",
                                                                    "data:image/jpeg;base64,${requestContent.jpegBase64}"
                                                                )
                                                            }
                                                        )
                                                    }
                                                )
                                            }
                                        )
                                    }
                                    requestContent is TextRequestContent.Plain -> {
                                        put("content", requestContent.text)
                                    }
                                    requestContent is TextRequestContent.Multimodal -> {
                                        put("content", requestContent.text)
                                    }
                                }
                            }
                        )
                    }
                )
                sessionId?.let { put("user", it) }
            }

            Log.i(
                TAG,
                "-> POST $base/v1/chat/completions model=${config.model} multimodal=$useMultimodal " +
                    "historyChars=${historyText.length} requestChars=" +
                    when (requestContent) {
                        is TextRequestContent.Plain -> requestContent.text.length
                        is TextRequestContent.Multimodal -> requestContent.text.length
                    }
            )
            val http = client ?: error("HttpClient missing")
            http.preparePost("$base/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                if (config.apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                }
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val err = response.bodyAsText()
                    throw IllegalStateException("HTTP ${response.status.value}: ${err.take(240)}")
                }
                val channel = response.bodyAsChannel()
                val assistant = StringBuilder()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank() || line.startsWith(":")) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = extractDelta(payload)
                    if (!delta.isNullOrEmpty()) {
                        assistant.append(delta)
                        _responses.emit(HermesResponse(event = "token", delta = delta))
                    }
                }
                val finalText = assistant.toString()
                if (finalText.isNotBlank()) {
                    history += ChatTurn(role = "assistant", content = finalText)
                }
                _responses.emit(
                    HermesResponse(
                        event = "done",
                        session_id = sessionId ?: config.sessionId,
                        content = finalText
                    )
                )
                Log.i(TAG, "stream done chars=${finalText.length}")
            }
            return true
        }

        try {
            val wantMulti = requestContent is TextRequestContent.Multimodal
            try {
                postOnce(useMultimodal = wantMulti)
            } catch (e: Exception) {
                if (wantMulti) {
                    Log.w(TAG, "multimodal failed, retry text-only: ${e.message}")
                    postOnce(useMultimodal = false)
                } else {
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stream failed: ${e.message}", e)
            if (history.lastOrNull()?.content == historyText && history.lastOrNull()?.role == "user") {
                history.removeAt(history.lastIndex)
            }
            _responses.emit(
                HermesResponse(event = "error", error = e.message ?: e::class.java.simpleName)
            )
            _lastError.value = e.message
        }
    }

    private fun extractDelta(payload: String): String? {
        return try {
            val chunk = json.decodeFromString(SseChunk.serializer(), payload)
            val delta = chunk.choices.firstOrNull()?.delta ?: return null
            // Do not stream reasoning/CoT into the chat UI
            delta.content?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            try {
                val root = json.parseToJsonElement(payload) as? JsonObject ?: return null
                val choices = root["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
                val first = choices.firstOrNull() as? JsonObject ?: return null
                val deltaObj = first["delta"] as? JsonObject ?: return null
                (deltaObj["content"] as? JsonPrimitive)?.contentOrNull
            } catch (_: Exception) {
                null
            }
        }
    }

    fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        history.clear()
        agentApiBase = null
        client?.close()
        client = null
        _connectionState.value = ConnectionState.Disconnected
        Log.i(TAG, "disconnect")
    }

    fun clearHistory() {
        history.clear()
    }

    private fun ensureClient() {
        if (client != null) return
        client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 180_000
            }
        }
    }

    private data class ChatTurn(val role: String, val content: String)

    @Serializable
    private data class SseChunk(
        val choices: List<SseChoice> = emptyList()
    )

    @Serializable
    private data class SseChoice(
        val delta: SseDelta? = null
    )

    @Serializable
    private data class SseDelta(
        val content: String? = null,
        val reasoning: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null
    )
}
