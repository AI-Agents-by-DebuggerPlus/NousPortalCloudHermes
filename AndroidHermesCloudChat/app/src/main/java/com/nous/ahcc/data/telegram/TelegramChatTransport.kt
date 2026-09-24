package com.nous.ahcc.data.telegram

import android.util.Base64
import android.util.Log
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.HermesResponse
import com.nous.ahcc.domain.model.MediaAttachmentWire
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Sends chat and audio through the Telegram Bot API.
 * Does not call getUpdates: Hermes gateway is the only poller for this bot.
 * The bot token is never written to logs.
 */
class TelegramChatTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var token: String = ""
    private var chatId: String = ""

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 60_000
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun connect(config: ConnectionConfig) {
        disconnect()
        token = config.telegramBotToken.trim()
        chatId = config.telegramChatId.trim()
        if (token.isEmpty() || chatId.isEmpty()) {
            _connectionState.value = ConnectionState.Error
            _lastError.value = "Нет Telegram bot token или chat id"
            return
        }
        _connectionState.value = ConnectionState.Connecting
        _lastError.value = null
        Log.i(TAG, "connect chatId=$chatId tokenLen=${token.length}")
        sessionJob = scope.launch {
            try {
                val username = getMe()
                Log.i(TAG, "getMe ok @$username")
                _connectionState.value = ConnectionState.Connected
                _lastError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = safe(e)
                Log.e(TAG, "connect failed: $message")
                _connectionState.value = ConnectionState.Error
                _lastError.value = message
            }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        _connectionState.value = ConnectionState.Disconnected
    }

    suspend fun sendMessage(text: String) {
        ensureConnected()
        val messageId = call(token) {
            client.submitForm(
                url = api("sendMessage"),
                formParameters = Parameters.build {
                    append("chat_id", chatId)
                    append("text", text)
                }
            ).bodyAsText()
        }
        Log.i(TAG, "sendMessage ok messageId=$messageId chars=${text.length}")
        _responses.emit(
            HermesResponse(event = "done", content = "Доставлено в Telegram (message_id=$messageId)")
        )
    }

    suspend fun sendAttachment(caption: String, wire: MediaAttachmentWire) {
        ensureConnected()
        val bytes = Base64.decode(wire.data, Base64.DEFAULT)
        val method = when (wire.kind.lowercase()) {
            "photo" -> "sendPhoto"
            "voice" -> "sendAudio"
            else -> "sendDocument"
        }
        val field = when (method) {
            "sendPhoto" -> "photo"
            "sendAudio" -> "audio"
            else -> "document"
        }
        val safeName = wire.name.replace("\"", "").ifBlank { "file" }
        val messageId = call(token) {
            client.post(api(method)) {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chatId)
                            if (caption.isNotBlank()) append("caption", caption)
                            append(
                                field,
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, wire.mime)
                                    append(HttpHeaders.ContentDisposition, "filename=\"$safeName\"")
                                }
                            )
                        }
                    )
                )
            }.bodyAsText()
        }
        Log.i(TAG, "$method ok messageId=$messageId bytes=${bytes.size} kind=${wire.kind}")
        _responses.emit(
            HermesResponse(event = "done", content = "Доставлено в Telegram (message_id=$messageId)")
        )
    }

    private suspend fun getMe(): String {
        val body = client.submitForm(url = api("getMe")).bodyAsText()
        val root = parseOk(body)
        return root["result"]?.jsonObject?.get("username")?.jsonPrimitive?.content ?: "bot"
    }

    private fun api(method: String) = "https://api.telegram.org/bot$token/$method"

    private fun ensureConnected() {
        if (_connectionState.value != ConnectionState.Connected) {
            error("Telegram не подключён")
        }
    }

    private fun safe(e: Exception): String {
        val raw = e.message ?: e::class.java.simpleName
        return if (token.isBlank()) raw else raw.replace(token, "***")
    }

    companion object {
        private const val TAG = "AHCC-TG"

        private suspend fun call(token: String, block: suspend () -> String): Long {
            return try {
                messageIdOf(parseOk(block()))
            } catch (e: IllegalStateException) {
                throw IllegalStateException(e.message?.replace(token, "***") ?: "Telegram error", e)
            }
        }

        private fun parseOk(text: String): kotlinx.serialization.json.JsonObject {
            val root = Json.parseToJsonElement(text).jsonObject
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
            if (!ok) {
                val description = root["description"]?.jsonPrimitive?.content ?: "Telegram error"
                throw IllegalStateException(description)
            }
            return root
        }

        private fun messageIdOf(root: kotlinx.serialization.json.JsonObject): Long {
            return root["result"]?.jsonObject
                ?.get("message_id")
                ?.jsonPrimitive
                ?.longOrNull
                ?: 0L
        }
    }
}
