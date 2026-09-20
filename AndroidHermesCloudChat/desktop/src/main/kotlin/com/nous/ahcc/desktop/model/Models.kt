package com.nous.ahcc.desktop.model

enum class ConnectionState {
    Disconnected, Connecting, Connected, Error
}

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false
)

data class ConnectionConfig(
    val apiKey: String = com.nous.ahcc.desktop.config.HermesConfig.API_KEY,
    val sessionId: String = com.nous.ahcc.desktop.config.HermesConfig.SESSION_ID,
    val inferenceBaseUrl: String = com.nous.ahcc.desktop.config.HermesConfig.INFERENCE_BASE_URL,
    val model: String = com.nous.ahcc.desktop.config.HermesConfig.MODEL
)

@kotlinx.serialization.Serializable
data class HermesResponse(
    val event: String,
    val delta: String? = null,
    val content: String? = null,
    val session_id: String? = null,
    val error: String? = null
)
