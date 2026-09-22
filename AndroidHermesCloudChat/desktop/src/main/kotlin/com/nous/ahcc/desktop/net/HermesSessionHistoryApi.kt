package com.nous.ahcc.desktop.net

import com.nous.ahcc.desktop.model.ConnectionConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ServerChatTurn(
    val role: String,
    val content: String,
)

/**
 * Loads Hermes Agent session transcripts via
 * `GET /api/sessions/{id}/messages` (API server or dashboard shape).
 */
object HermesSessionHistoryApi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun candidateBases(config: ConnectionConfig, includeLoopback: Boolean = true): List<String> {
        val out = linkedSetOf<String>()
        config.agentApiBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { out += it }
        if (includeLoopback) {
            out += "http://127.0.0.1:8642"
            out += "http://localhost:8642"
        }
        val host = config.host.trim()
        if (host.isNotEmpty() &&
            !host.equals("127.0.0.1", ignoreCase = true) &&
            !host.equals("localhost", ignoreCase = true)
        ) {
            if (!config.useTls) {
                out += "http://$host:8642"
            }
            val scheme = if (config.useTls) "https" else "http"
            val authority = when {
                config.useTls && config.port == 443 -> host
                !config.useTls && config.port == 80 -> host
                else -> "$host:${config.port}"
            }
            out += "$scheme://$authority"
        }
        return out.toList()
    }

    suspend fun loadHistory(
        http: HttpClient,
        config: ConnectionConfig,
        includeLoopback: Boolean = true,
    ): Pair<String, List<ServerChatTurn>> {
        val sessionId = config.sessionId.trim().ifBlank { "default" }
        var lastError: Exception? = null
        for (base in candidateBases(config, includeLoopback)) {
            try {
                ensureSession(http, base, config, sessionId)
                val turns = fetchMessages(http, base, config, sessionId)
                return base to turns
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No Agent API base URL configured")
    }

    private suspend fun ensureSession(
        http: HttpClient,
        base: String,
        config: ConnectionConfig,
        sessionId: String,
    ) {
        val get = http.get("$base/api/sessions/$sessionId") {
            auth(config)
        }
        if (get.status.isSuccess() || get.status.value == 404) {
            if (get.status.value == 404) {
                val created = http.post("$base/api/sessions") {
                    auth(config)
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("id", sessionId)
                                put("title", "AHCC $sessionId")
                            }
                        )
                    )
                }
                if (!created.status.isSuccess() && created.status.value != 409) {
                    error("Create session HTTP ${created.status.value}: ${created.bodyAsText().take(180)}")
                }
            }
            return
        }
        if (get.status.value == 401 || get.status.value == 403) {
            error("Agent API unauthorized at $base (need API_SERVER_KEY)")
        }
        error("Session probe HTTP ${get.status.value} at $base")
    }

    private suspend fun fetchMessages(
        http: HttpClient,
        base: String,
        config: ConnectionConfig,
        sessionId: String,
    ): List<ServerChatTurn> {
        val response = http.get("$base/api/sessions/$sessionId/messages") {
            auth(config)
            parameter("limit", 200)
            parameter("order", "oldest")
            parameter("offset", 0)
        }
        val body = response.bodyAsText()
        if (response.status.value == 404) return emptyList()
        if (!response.status.isSuccess()) {
            error("History HTTP ${response.status.value}: ${body.take(180)}")
        }
        return parseMessages(body)
    }

    fun parseMessages(body: String): List<ServerChatTurn> {
        val root = json.parseToJsonElement(body).jsonObject
        val array = root["data"]?.jsonArray
            ?: root["messages"]?.jsonArray
            ?: JsonArray(emptyList())
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val role = obj["role"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return@mapNotNull null
            if (role != "user" && role != "assistant" && role != "system") return@mapNotNull null
            val text = contentToText(obj["content"]).trim()
            if (text.isEmpty()) return@mapNotNull null
            ServerChatTurn(role = role, content = text)
        }
    }

    private fun contentToText(content: kotlinx.serialization.json.JsonElement?): String {
        if (content == null) return ""
        return when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("\n") { part ->
                val obj = part as? JsonObject ?: return@joinToString part.toString()
                obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            is JsonObject -> content["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> content.toString()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(config: ConnectionConfig) {
        if (config.apiKey.isNotBlank()) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        }
    }
}
