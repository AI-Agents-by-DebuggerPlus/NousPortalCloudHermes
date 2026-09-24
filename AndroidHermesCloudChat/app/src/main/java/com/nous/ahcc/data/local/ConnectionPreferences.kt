package com.nous.ahcc.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nous.ahcc.config.HermesConfig
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.TransportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ahcc_prefs")

class ConnectionPreferences(private val context: Context) {

    private object Keys {
        val host = stringPreferencesKey("host")
        val port = intPreferencesKey("port")
        val apiKey = stringPreferencesKey("api_key")
        val sessionId = stringPreferencesKey("session_id")
        val useTls = booleanPreferencesKey("use_tls")
        val keepAlive = booleanPreferencesKey("keep_alive")
        val transport = stringPreferencesKey("transport")
        val inferenceBaseUrl = stringPreferencesKey("inference_base_url")
        val model = stringPreferencesKey("model")
        val agentApiBaseUrl = stringPreferencesKey("agent_api_base_url")
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val supabaseAnonKey = stringPreferencesKey("supabase_anon_key")
        val telegramBotToken = stringPreferencesKey("telegram_bot_token_v2")
        val telegramChatId = stringPreferencesKey("telegram_chat_id")
        val telegramApiId = intPreferencesKey("telegram_api_id")
        val telegramApiHash = stringPreferencesKey("telegram_api_hash")
        val telegramPhone = stringPreferencesKey("telegram_phone")
        val ttsEnglishVoice = stringPreferencesKey("tts_english_voice")
        val ttsRussianVoice = stringPreferencesKey("tts_russian_voice")
    }

    data class TelegramTarget(val botToken: String, val chatId: String)

    val telegramTargetFlow: Flow<TelegramTarget> = context.dataStore.data.map { prefs ->
        TelegramTarget(
            botToken = prefs[Keys.telegramBotToken] ?: HermesConfig.TELEGRAM_BOT_TOKEN,
            chatId = prefs[Keys.telegramChatId] ?: HermesConfig.TELEGRAM_CHAT_ID
        )
    }

    data class TtsVoices(val english: String, val russian: String)

    val ttsVoiceFlow: Flow<TtsVoices> = context.dataStore.data.map { prefs ->
        TtsVoices(
            english = prefs[Keys.ttsEnglishVoice].orEmpty(),
            russian = prefs[Keys.ttsRussianVoice].orEmpty(),
        )
    }

    suspend fun saveTtsVoices(english: String, russian: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ttsEnglishVoice] = english.trim()
            prefs[Keys.ttsRussianVoice] = russian.trim()
        }
    }

    data class TelegramLogin(val apiId: Int, val apiHash: String, val phone: String)

    val telegramLoginFlow: Flow<TelegramLogin> = context.dataStore.data.map { prefs ->
        TelegramLogin(
            apiId = prefs[Keys.telegramApiId]?.takeIf { it != 0 } ?: HermesConfig.TELEGRAM_API_ID,
            apiHash = prefs[Keys.telegramApiHash]?.takeIf { it.isNotBlank() }
                ?: HermesConfig.TELEGRAM_API_HASH,
            phone = prefs[Keys.telegramPhone]?.takeIf { it.isNotBlank() } ?: HermesConfig.TELEGRAM_PHONE
        )
    }

    suspend fun saveTelegramLogin(apiId: Int, apiHash: String, phone: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.telegramApiId] = apiId
            prefs[Keys.telegramApiHash] = apiHash.trim()
            prefs[Keys.telegramPhone] = phone.trim()
        }
    }

    suspend fun saveTelegram(botToken: String, chatId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.telegramBotToken] = botToken.trim()
            prefs[Keys.telegramChatId] = chatId.trim()
        }
    }

    val configFlow: Flow<ConnectionConfig> = context.dataStore.data.map { prefs ->
        ConnectionConfig(
            host = prefs[Keys.host] ?: HermesConfig.HOST,
            port = prefs[Keys.port] ?: HermesConfig.PORT,
            apiKey = prefs[Keys.apiKey] ?: HermesConfig.API_KEY,
            sessionId = prefs[Keys.sessionId] ?: HermesConfig.SESSION_ID,
            useTls = prefs[Keys.useTls] ?: HermesConfig.USE_TLS,
            keepAliveInBackground = prefs[Keys.keepAlive] ?: true,
            // Stored http_sse is ignored: AHCC talks to Hermes Agent over WebSocket only.
            transport = TransportMode.WebSocket,
            inferenceBaseUrl = prefs[Keys.inferenceBaseUrl] ?: HermesConfig.INFERENCE_BASE_URL,
            model = prefs[Keys.model] ?: HermesConfig.MODEL,
            agentApiBaseUrl = prefs[Keys.agentApiBaseUrl] ?: HermesConfig.AGENT_API_BASE_URL,
            supabaseUrl = (prefs[Keys.supabaseUrl] ?: HermesConfig.SUPABASE_URL)
                .ifBlank { HermesConfig.SUPABASE_URL },
            supabaseAnonKey = (prefs[Keys.supabaseAnonKey] ?: HermesConfig.SUPABASE_ANON_KEY)
                .ifBlank { HermesConfig.SUPABASE_ANON_KEY },
            telegramBotToken = (prefs[Keys.telegramBotToken] ?: HermesConfig.TELEGRAM_BOT_TOKEN)
                .ifBlank { HermesConfig.TELEGRAM_BOT_TOKEN },
            telegramChatId = (prefs[Keys.telegramChatId] ?: HermesConfig.TELEGRAM_CHAT_ID)
                .ifBlank { HermesConfig.TELEGRAM_CHAT_ID }
        )
    }

    suspend fun save(config: ConnectionConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.host] = config.host.trim()
            prefs[Keys.port] = config.port
            prefs[Keys.apiKey] = config.apiKey.trim()
            prefs[Keys.sessionId] = config.sessionId.trim().ifBlank { HermesConfig.SESSION_ID }
            prefs[Keys.useTls] = config.useTls
            prefs[Keys.keepAlive] = config.keepAliveInBackground
            prefs[Keys.transport] = config.transport.toStorage()
            prefs[Keys.inferenceBaseUrl] = config.inferenceBaseUrl.trim()
                .ifBlank { HermesConfig.INFERENCE_BASE_URL }
            prefs[Keys.model] = config.model.trim().ifBlank { HermesConfig.MODEL }
            prefs[Keys.agentApiBaseUrl] = config.agentApiBaseUrl.trim()
            prefs[Keys.supabaseUrl] = config.supabaseUrl.trim()
            prefs[Keys.supabaseAnonKey] = config.supabaseAnonKey.trim()
            prefs[Keys.telegramBotToken] = config.telegramBotToken.trim()
            prefs[Keys.telegramChatId] = config.telegramChatId.trim()
        }
    }
}
