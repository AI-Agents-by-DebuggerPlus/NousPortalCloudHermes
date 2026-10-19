package com.nous.ahcc.desktop.model

enum class ConnectionState {
    Disconnected, Connecting, Connected, Error
}

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false,
    /** True after a finished assistant turn (Telegram-style “delivered”). */
    val isComplete: Boolean = false,
    val inlineKeyboard: TelegramInlineKeyboard? = null,
    /** Local path for an attached file (sent or downloaded inbound). */
    val localAttachmentPath: String? = null,
    val attachmentName: String? = null,
)

data class ChatAttachmentRef(
    val localPath: String,
    val displayName: String,
)

data class TelegramReply(
    val text: String,
    val keyboard: TelegramInlineKeyboard? = null,
    val attachment: ChatAttachmentRef? = null,
)

data class ConnectionConfig(
    val channel: ChatChannel = ChatChannel.Telegram,
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
    val telegramBotToken: String = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_BOT_TOKEN,
    val telegramChatId: String = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_CHAT_ID,
    val telegramBotUsername: String = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_BOT_USERNAME,
    val telegramApiId: Int = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_API_ID,
    val telegramApiHash: String = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_API_HASH,
    val telegramPhone: String = com.nous.ahcc.desktop.config.HermesConfig.TELEGRAM_PHONE,
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
    @kotlinx.serialization.Transient
    val inlineKeyboard: TelegramInlineKeyboard? = null,
    @kotlinx.serialization.Transient
    val attachment: ChatAttachmentRef? = null,
)

@kotlinx.serialization.Serializable
data class HermesHistoryItem(
    val role: String,
    val content: String,
)
