package com.nous.ahcc.data.supabase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Supabase PostgREST client for ahcc_messages + ahcc_files.
 */
class AhccSupabaseClient(
    private val baseUrl: String,
    private val anonKey: String,
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    val isConfigured: Boolean
        get() = root.isNotBlank() && anonKey.isNotBlank()

    suspend fun fetchLastSessionId(): String? {
        if (!isConfigured) return null
        val rows: List<AhccMessageRow> = http.get("$root/rest/v1/ahcc_messages") {
            authHeaders()
            url.parameters.append("select", "session_id,created_at,content")
            url.parameters.append("order", "created_at.desc")
            url.parameters.append("limit", "1")
        }.body()
        return rows.firstOrNull()?.sessionId?.takeIf { it.isNotBlank() }
    }

    suspend fun fetchSessionMessages(sessionId: String): List<AhccMessageRow> {
        if (!isConfigured || sessionId.isBlank()) return emptyList()
        return http.get("$root/rest/v1/ahcc_messages") {
            authHeaders()
            url.parameters.append("select", "*")
            url.parameters.append("session_id", "eq.$sessionId")
            url.parameters.append("order", "created_at.asc")
        }.body()
    }

    /**
     * Resolve Hermes session id: last row in Supabase, or create a new one + marker.
     */
    suspend fun resolveOrCreateSession(
        forceNew: Boolean,
        preferredId: String?,
        senderName: String,
        recipientName: String = "Hermes",
    ): String {
        if (!isConfigured) {
            return preferredId?.takeIf { it.isNotBlank() }
                ?: "ahcc_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        }
        if (!forceNew) {
            fetchLastSessionId()?.let { return it }
            preferredId?.takeIf { it.isNotBlank() }?.let { existing ->
                // Prefer local id only when table is empty вЂ” still mark as new session.
                insertMarker(existing, senderName, recipientName)
                return existing
            }
        }
        val newId = "ahcc_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        insertMarker(newId, senderName, recipientName)
        return newId
    }

    suspend fun insertMarker(sessionId: String, senderName: String, recipientName: String = "Hermes") {
        insert(
            sessionId = sessionId,
            senderName = senderName,
            recipientName = recipientName,
            content = NEW_SESSION_CONTENT,
        )
    }

    suspend fun insert(
        sessionId: String,
        senderName: String,
        content: String,
        recipientName: String? = "Hermes",
        senderId: String? = null,
    ) {
        if (!isConfigured || sessionId.isBlank() || content.isBlank()) return
        val res = http.post("$root/rest/v1/ahcc_messages") {
            authHeaders()
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(
                AhccMessageInsert(
                    senderId = senderId,
                    senderName = senderName,
                    content = content,
                    recipientName = recipientName,
                    sessionId = sessionId,
                )
            )
        }
        if (!res.status.isSuccess()) {
            throw IllegalStateException("Supabase insert HTTP ${res.status.value}: ${res.bodyAsText().take(240)}")
        }
    }

    /**
     * Upload a text/markdown file for peer clients. Returns chat marker `[AHCC_FILE]{...}`.
     */
    suspend fun uploadTextFile(
        sessionId: String,
        senderName: String,
        fileName: String,
        text: String,
        mime: String = "text/markdown",
        recipientName: String? = "Hermes",
    ): String {
        if (!isConfigured) throw IllegalStateException("Supabase is not configured")
        if (sessionId.isBlank()) throw IllegalStateException("session_id is empty")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_FILE_BYTES) {
            throw IllegalStateException("File too large (max ${MAX_TEXT_FILE_BYTES / 1024} KiB)")
        }
        val fileId = UUID.randomUUID().toString()
        val res = http.post("$root/rest/v1/ahcc_files") {
            authHeaders()
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(
                AhccFileInsert(
                    id = fileId,
                    sessionId = sessionId,
                    senderName = senderName,
                    fileName = fileName,
                    mime = mime,
                    content = text,
                    byteSize = bytes.size,
                )
            )
        }
        if (!res.status.isSuccess()) {
            throw IllegalStateException("Supabase file insert HTTP ${res.status.value}: ${res.bodyAsText().take(240)}")
        }
        val marker = formatFileMarker(fileId, fileName, mime)
        insert(
            sessionId = sessionId,
            senderName = senderName,
            content = marker,
            recipientName = recipientName,
        )
        return marker
    }

    suspend fun fetchFile(fileId: String): AhccFileRow? {
        if (!isConfigured || fileId.isBlank()) return null
        val rows: List<AhccFileRow> = http.get("$root/rest/v1/ahcc_files") {
            authHeaders()
            url.parameters.append("select", "*")
            url.parameters.append("id", "eq.$fileId")
            url.parameters.append("limit", "1")
        }.body()
        return rows.firstOrNull()
    }

    fun close() = http.close()

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("apikey", anonKey)
        header("Authorization", "Bearer $anonKey")
    }

    companion object {
        const val NEW_SESSION_CONTENT = "new session"
        const val FILE_MARKER_PREFIX = "[AHCC_FILE]"
        const val SENDER_DESKTOP = "WPF User"
        const val SENDER_ANDROID = "AndroidChat"
        const val SENDER_HERMES = "Hermes"
        const val MAX_TEXT_FILE_BYTES = 512 * 1024

        fun formatFileMarker(id: String, name: String, mime: String): String =
            """$FILE_MARKER_PREFIX{"id":"$id","name":${jsonString(name)},"mime":${jsonString(mime)}}"""

        fun parseFileMarker(content: String): Triple<String, String, String>? {
            val trimmed = content.trim()
            if (!trimmed.startsWith(FILE_MARKER_PREFIX)) return null
            val jsonPart = trimmed.removePrefix(FILE_MARKER_PREFIX).trim()
            return runCatching {
                val obj = Json { ignoreUnknownKeys = true }.decodeFromString<AhccFileRef>(jsonPart)
                if (obj.id.isBlank()) null else Triple(obj.id, obj.name, obj.mime)
            }.getOrNull()
        }

        private fun jsonString(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

@Serializable
data class AhccMessageRow(
    val id: String? = null,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("recipient_name") val recipientName: String? = null,
    @SerialName("session_id") val sessionId: String = "",
)

@Serializable
data class AhccMessageInsert(
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String,
    val content: String,
    @SerialName("recipient_name") val recipientName: String? = null,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class AhccFileInsert(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("sender_name") val senderName: String,
    @SerialName("file_name") val fileName: String,
    val mime: String,
    val content: String,
    @SerialName("byte_size") val byteSize: Int,
)

@Serializable
data class AhccFileRow(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("sender_name") val senderName: String = "",
    @SerialName("file_name") val fileName: String = "",
    val mime: String = "text/markdown",
    val content: String = "",
    @SerialName("byte_size") val byteSize: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class AhccFileRef(
    val id: String = "",
    val name: String = "",
    val mime: String = "text/markdown",
)

