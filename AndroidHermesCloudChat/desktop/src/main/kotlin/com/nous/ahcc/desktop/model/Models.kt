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
    val model: String = com.nous.ahcc.desktop.config.HermesConfig.MODEL,
    val host: String = com.nous.ahcc.desktop.config.HermesConfig.HOST,
    val port: Int = com.nous.ahcc.desktop.config.HermesConfig.PORT,
    val useTls: Boolean = com.nous.ahcc.desktop.config.HermesConfig.USE_TLS,
    /** Hermes Agent API root for server-side session history. Empty = auto-detect. */
    val agentApiBaseUrl: String = com.nous.ahcc.desktop.config.HermesConfig.AGENT_API_BASE_URL,
    /** Supabase project URL, e.g. https://xxxx.supabase.co */
    val supabaseUrl: String = com.nous.ahcc.desktop.config.HermesConfig.SUPABASE_URL,
    /** Supabase anon (public) key */
    val supabaseAnonKey: String = com.nous.ahcc.desktop.config.HermesConfig.SUPABASE_ANON_KEY,
)

@kotlinx.serialization.Serializable
data class HermesResponse(
    val event: String,
    val delta: String? = null,
    val content: String? = null,
    val session_id: String? = null,
    val error: String? = null,
    /** Populated for event=history — server transcript turns as role/content pairs. */
    val history: List<HermesHistoryItem>? = null,
)

@kotlinx.serialization.Serializable
data class HermesHistoryItem(
    val role: String,
    val content: String,
)
