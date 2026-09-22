package com.nous.ahcc.desktop.net

import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.HermesHistoryItem
import com.nous.ahcc.desktop.model.HermesResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class HermesHttpSseClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private var client: HttpClient? = null
    private var currentConfig: ConnectionConfig? = null
    private var streamJob: Job? = null
    private val history = mutableListOf<Pair<String, String>>()
    /** When set, chat goes through Hermes Agent session API (server-owned history). */
    private var agentApiBase: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
                    error("Auth failed: HTTP ${response.status.value}")
                }

                // Server session history is intentionally not loaded — current session only.
                history.clear()
                agentApiBase = null

                _connectionState.value = ConnectionState.Connected
                _lastError.value = null
                println("[AHCC-HTTP] CONNECTED $base model=${config.model}")
            } catch (e: Exception) {
                _lastError.value = e.message
                _connectionState.value = ConnectionState.Error
                println("[AHCC-HTTP] connect failed: ${e.message}")
            }
        }
    }

    suspend fun sendMessage(text: String) {
        sendUserTurn(historyText = text, requestText = text)
    }

    /** Send AHCC_MEDIA_V1 envelope; history keeps a short placeholder. */
    suspend fun sendMedia(envelopeText: String, historyPlaceholder: String) {
        sendUserTurn(historyText = historyPlaceholder, requestText = envelopeText)
    }

    private suspend fun sendUserTurn(historyText: String, requestText: String) {
        val config = currentConfig ?: error("Not connected")
        if (_connectionState.value != ConnectionState.Connected) error("Not connected")
        sendMutex.withLock {
            streamJob?.cancel()
            streamJob = scope.launch {
                val agentBase = agentApiBase
                if (agentBase != null) {
                    streamAgentSession(config, agentBase, historyText, requestText)
                } else {
                    streamInference(config, historyText, requestText)
                }
            }
            streamJob?.join()
        }
    }

    private suspend fun streamAgentSession(
        config: ConnectionConfig,
        agentBase: String,
        historyText: String,
        requestText: String,
    ) {
        history += "user" to historyText
        val http = client ?: error("no client")
        val body = buildJsonObject {
            put("input", requestText)
        }
        try {
            http.preparePost("$agentBase/api/sessions/${config.sessionId}/chat/stream") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
                }
                val channel = response.bodyAsChannel()
                val assistant = StringBuilder()
                var eventName = ""
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    if (line.startsWith(":")) continue
                    if (line.startsWith("event:")) {
                        eventName = line.removePrefix("event:").trim()
                        continue
                    }
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    when (eventName) {
                        "assistant.delta", "message.delta" -> {
                            val delta = extractAgentDelta(payload) ?: continue
                            assistant.append(delta)
                            _responses.emit(HermesResponse(event = "token", delta = delta))
                        }
                        "run.failed", "error" -> {
                            val err = extractAgentError(payload) ?: payload
                            throw IllegalStateException(err)
                        }
                        "run.completed", "run.cancelled" -> {
                            // terminal
                        }
                    }
                }
                val finalText = assistant.toString()
                if (finalText.isNotBlank()) history += "assistant" to finalText
                _responses.emit(HermesResponse(event = "done", content = finalText, session_id = config.sessionId))
            }
        } catch (e: Exception) {
            if (history.lastOrNull()?.first == "user" && history.lastOrNull()?.second == historyText) {
                history.removeAt(history.lastIndex)
            }
            // Fall back to Inference if agent stream fails mid-flight.
            println("[AHCC-HTTP] agent session chat failed, fallback inference: ${e.message}")
            agentApiBase = null
            streamInference(config, historyText, requestText)
        }
    }

    private suspend fun streamInference(config: ConnectionConfig, historyText: String, requestText: String) {
        if (history.lastOrNull()?.first != "user" || history.lastOrNull()?.second != historyText) {
            history += "user" to historyText
        }
        val base = config.inferenceBaseUrl.trimEnd('/')
        val body = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put(
                "messages",
                buildJsonArray {
                    history.dropLast(1).forEach { (role, content) ->
                        add(buildJsonObject {
                            put("role", role)
                            put("content", content)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", requestText)
                    })
                }
            )
            put("user", config.sessionId)
        }
        val http = client ?: error("no client")
        try {
            http.preparePost("$base/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
                }
                val channel = response.bodyAsChannel()
                val assistant = StringBuilder()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = extractContent(payload) ?: continue
                    assistant.append(delta)
                    _responses.emit(HermesResponse(event = "token", delta = delta))
                }
                val finalText = assistant.toString()
                if (finalText.isNotBlank()) history += "assistant" to finalText
                _responses.emit(HermesResponse(event = "done", content = finalText, session_id = config.sessionId))
            }
        } catch (e: Exception) {
            if (history.lastOrNull()?.first == "user" && history.lastOrNull()?.second == historyText) {
                history.removeAt(history.lastIndex)
            }
            _responses.emit(HermesResponse(event = "error", error = e.message))
            _lastError.value = e.message
        }
    }

    private fun extractAgentDelta(payload: String): String? = try {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return null
        (root["delta"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: (root["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    private fun extractAgentError(payload: String): String? = try {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return null
        (root["error"] as? JsonPrimitive)?.contentOrNull
            ?: (root["message"] as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private fun extractContent(payload: String): String? = try {
        val root = json.parseToJsonElement(payload) as? JsonObject ?: return null
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray ?: return null
        val delta = (choices.firstOrNull() as? JsonObject)?.get("delta") as? JsonObject ?: return null
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    fun disconnect() {
        streamJob?.cancel()
        history.clear()
        agentApiBase = null
        client?.close()
        client = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearHistory() = history.clear()

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
}
