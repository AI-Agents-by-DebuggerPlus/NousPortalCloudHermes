package com.nous.ahcc.domain.model

/**
 * Outbound request from Android → Hermes Agent (see Docs/Gemini protocol).
 */
@kotlinx.serialization.Serializable
data class HermesRequest(
    val event: String = "message",
    val session_id: String? = null,
    val content: String? = null,
    val data: HermesMessageData? = null,
    /** Optional AHCC media attachments (Hermes Agent may consume; text envelope remains in content). */
    val attachments: List<MediaAttachmentWire>? = null
)

@kotlinx.serialization.Serializable
data class HermesMessageData(
    val role: String = "user",
    val content: String
)

/**
 * Inbound frames from Hermes (token streaming, tool calls, done, errors).
 */
@kotlinx.serialization.Serializable
data class HermesResponse(
    val event: String,
    val delta: String? = null,
    val content: String? = null,
    val name: String? = null,
    val args: Map<String, String>? = null,
    val tool_call: ToolCallPayload? = null,
    val session_id: String? = null,
    val error: String? = null,
    val data: HermesMessageData? = null,
    /** event=history — server transcript */
    val history: List<HermesHistoryItem>? = null
)

@kotlinx.serialization.Serializable
data class HermesHistoryItem(
    val role: String,
    val content: String
)

@kotlinx.serialization.Serializable
data class ToolCallPayload(
    val name: String? = null,
    val args: Map<String, String>? = null
)

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false,
    val toolName: String? = null,
    val attachmentKind: MediaKind? = null,
    val attachmentName: String? = null,
    val localMediaPath: String? = null,
    val inboundMedia: List<InboundMedia> = emptyList()
)

enum class MessageRole {
    User,
    Assistant,
    System,
    Tool
}

enum class TransportMode {
    /** OpenAI-compatible streaming against Nous Inference (works with sk-nous). */
    HttpSse,
    /** Legacy / local Hermes WebSocket bridge (/v1/ws/chat). */
    WebSocket;

    companion object {
        fun fromStorage(value: String?): TransportMode = when (value) {
            "websocket", "ws" -> WebSocket
            else -> HttpSse
        }
    }

    fun toStorage(): String = when (this) {
        HttpSse -> "http_sse"
        WebSocket -> "websocket"
    }
}

data class ConnectionConfig(
    val host: String = com.nous.ahcc.config.HermesConfig.HOST,
    val port: Int = com.nous.ahcc.config.HermesConfig.PORT,
    val apiKey: String = com.nous.ahcc.config.HermesConfig.API_KEY,
    val sessionId: String = com.nous.ahcc.config.HermesConfig.SESSION_ID,
    val useTls: Boolean = com.nous.ahcc.config.HermesConfig.USE_TLS,
    val keepAliveInBackground: Boolean = true,
    val transport: TransportMode = TransportMode.fromStorage(
        com.nous.ahcc.config.HermesConfig.TRANSPORT
    ),
    val inferenceBaseUrl: String = com.nous.ahcc.config.HermesConfig.INFERENCE_BASE_URL,
    val model: String = com.nous.ahcc.config.HermesConfig.MODEL,
    /** Hermes Agent API root for server session history. Empty = auto-detect. */
    val agentApiBaseUrl: String = com.nous.ahcc.config.HermesConfig.AGENT_API_BASE_URL,
    /** Supabase project URL, e.g. https://xxxx.supabase.co */
    val supabaseUrl: String = com.nous.ahcc.config.HermesConfig.SUPABASE_URL,
    /** Supabase anon (public) key */
    val supabaseAnonKey: String = com.nous.ahcc.config.HermesConfig.SUPABASE_ANON_KEY
)
