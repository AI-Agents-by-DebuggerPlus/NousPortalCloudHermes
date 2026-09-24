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
    /**
     * Legacy Nous Inference HTTP SSE. Not exposed in the app UI.
     * Model is client-supplied only on this path (`ConnectionConfig.model`).
     */
    HttpSse,
    /** Hermes Agent event stream: /v1/ws/chat. Model is chosen on the agent, not by the client. */
    WebSocket;

    companion object {
        fun fromStorage(value: String?): TransportMode = when (value?.lowercase()) {
            "http_sse", "http" -> HttpSse
            else -> WebSocket
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
    /** Production default: Hermes Agent over WebSocket. HTTP SSE is legacy-only. */
    val transport: TransportMode = TransportMode.WebSocket,
    /** Used only by the unused HTTP SSE client. The agent picks its own model. */
    val inferenceBaseUrl: String = com.nous.ahcc.config.HermesConfig.INFERENCE_BASE_URL,
    /** Legacy HTTP SSE model id. Not sent on the WebSocket path. */
    val model: String = com.nous.ahcc.config.HermesConfig.MODEL,
    /** Hermes Agent API root for server session history. Empty = auto-detect. */
    val agentApiBaseUrl: String = com.nous.ahcc.config.HermesConfig.AGENT_API_BASE_URL,
    /** Supabase project URL, e.g. https://xxxx.supabase.co */
    val supabaseUrl: String = com.nous.ahcc.config.HermesConfig.SUPABASE_URL,
    /** Supabase anon (public) key */
    val supabaseAnonKey: String = com.nous.ahcc.config.HermesConfig.SUPABASE_ANON_KEY,
    /** Telegram bot that carries chat and voice. Not the Inference sk-nous key. */
    val telegramBotToken: String = com.nous.ahcc.config.HermesConfig.TELEGRAM_BOT_TOKEN,
    val telegramChatId: String = com.nous.ahcc.config.HermesConfig.TELEGRAM_CHAT_ID
)
