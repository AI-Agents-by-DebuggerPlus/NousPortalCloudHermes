package com.nous.ahcc.data.telegram

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TelegramSendResult(
    val messageId: Long?,
)

/**
 * Uploads a recorded audio file with Telegram Bot API sendAudio.
 * The bot token is never written to logs.
 */
class TelegramAudioClient {
    suspend fun sendAudio(
        botToken: String,
        chatId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        caption: String,
    ): TelegramSendResult {
        val token = botToken.trim()
        val chat = chatId.trim()
        require(token.isNotEmpty()) { "Нужен Telegram bot token" }
        require(chat.isNotEmpty()) { "Нужен chat id" }
        val safeName = fileName.replace("\"", "").ifBlank { "voice.m4a" }
        val client = HttpClient(CIO) {
            engine {
                requestTimeout = 60_000
            }
        }
        try {
            val response = client.post("https://api.telegram.org/bot$token/sendAudio") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("chat_id", chat)
                            append("caption", caption)
                            append(
                                "audio",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, mime)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"$safeName\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
            return parse(response.bodyAsText())
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            val msg = (e.message ?: e::class.java.simpleName).replace(token, "***")
            throw IllegalStateException(msg, e)
        } finally {
            client.close()
        }
    }

    private fun parse(text: String): TelegramSendResult {
        val root = Json.parseToJsonElement(text).jsonObject
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            val description = root["description"]?.jsonPrimitive?.content
            throw IllegalStateException(description ?: "Telegram sendAudio failed")
        }
        val messageId = root["result"]?.jsonObject
            ?.get("message_id")
            ?.jsonPrimitive
            ?.longOrNull
        return TelegramSendResult(messageId = messageId)
    }
}
