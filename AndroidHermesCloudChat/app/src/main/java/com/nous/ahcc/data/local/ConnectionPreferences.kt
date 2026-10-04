package com.nous.ahcc.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nous.ahcc.config.HermesConfig
import com.nous.ahcc.domain.model.Addressee
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.FlashcardDisplaySettings
import com.nous.ahcc.domain.model.TransportMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val ttsFavoriteVoices = stringSetPreferencesKey("tts_favorite_voices")
        val localTranscription = booleanPreferencesKey("local_transcription")
        val addresseeCatalogJson = stringPreferencesKey("addressee_catalog_json")
        val defaultAddresseeId = stringPreferencesKey("default_addressee_id")
        val flashcardEnglishSp = intPreferencesKey("flashcard_en_sp")
        val flashcardRussianSp = intPreferencesKey("flashcard_ru_sp")
        val flashcardEnglishColor = intPreferencesKey("flashcard_en_color")
        val flashcardRussianColor = intPreferencesKey("flashcard_ru_color")
        val flashcardBackground = intPreferencesKey("flashcard_bg_color")
        val flashcardBlankAfterSec = intPreferencesKey("flashcard_blank_after_sec")
        val flashcardSpeechPauseSec = intPreferencesKey("flashcard_speech_pause_sec")
        val flashcardEnTopPad = intPreferencesKey("flashcard_en_top_pad")
        val flashcardEnRuGap = intPreferencesKey("flashcard_en_ru_gap")
        val flashcardRuSmallerPct = intPreferencesKey("flashcard_ru_smaller_pct")
    }

    data class AddresseeSettings(
        val catalog: List<Addressee>,
        val defaultAddresseeId: String,
    )

    val addresseeSettingsFlow: Flow<AddresseeSettings> = context.dataStore.data.map { prefs ->
        val catalog = Addressee.decodeCatalog(prefs[Keys.addresseeCatalogJson]).ifEmpty { Addressee.defaultCatalog }
        val storedDefault = prefs[Keys.defaultAddresseeId]?.let { id ->
            if (id == "liaison") Addressee.MAIN_AGENT_ID else id
        }
        val defaultId = storedDefault?.takeIf { id -> catalog.any { it.id == id } }
            ?: Addressee.MAIN_AGENT_ID
        AddresseeSettings(catalog = catalog, defaultAddresseeId = defaultId)
    }

    val addresseeCatalogFlow: Flow<List<Addressee>> = addresseeSettingsFlow.map { it.catalog }

    suspend fun saveAddresseeCatalog(list: List<Addressee>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.addresseeCatalogJson] = Addressee.encodeCatalog(list)
        }
    }

    suspend fun readAddresseeCatalog(): List<Addressee> {
        val prefs = context.dataStore.data.first()
        return Addressee.decodeCatalog(prefs[Keys.addresseeCatalogJson]).ifEmpty { Addressee.defaultCatalog }
    }

    suspend fun addAddressee(addressee: Addressee): List<Addressee> {
        val current = readAddresseeCatalog()
        if (current.any { it.id.equals(addressee.id, ignoreCase = true) }) return current
        val next = current + addressee
        saveAddresseeCatalog(next)
        return next
    }

    suspend fun removeAddressee(id: String): List<Addressee> {
        if (id == Addressee.MAIN_AGENT_ID) return readAddresseeCatalog()
        val prefs = context.dataStore.data.first()
        if (prefs[Keys.defaultAddresseeId] == id) {
            setDefaultAddresseeId(Addressee.MAIN_AGENT_ID)
        }
        val next = readAddresseeCatalog().filterNot { it.id == id }
        val normalized = if (next.any { it.id == Addressee.MAIN_AGENT_ID }) {
            next
        } else {
            Addressee.defaultCatalog
        }
        saveAddresseeCatalog(normalized)
        return normalized
    }

    suspend fun setDefaultAddresseeId(id: String) {
        val catalog = readAddresseeCatalog()
        val resolved = id.takeIf { candidate -> catalog.any { it.id == candidate } } ?: Addressee.MAIN_AGENT_ID
        context.dataStore.edit { prefs ->
            prefs[Keys.defaultAddresseeId] = resolved
        }
    }

    suspend fun readDefaultAddressee(catalog: List<Addressee>): Addressee {
        val prefs = context.dataStore.data.first()
        val stored = prefs[Keys.defaultAddresseeId]?.let { raw ->
            if (raw == "liaison") Addressee.MAIN_AGENT_ID else raw
        }
        val id = stored?.takeIf { candidate -> catalog.any { it.id == candidate } }
            ?: Addressee.MAIN_AGENT_ID
        return catalog.find { it.id == id } ?: Addressee.MAIN_AGENT
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

    val localTranscriptionFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.localTranscription] ?: false
    }

    val flashcardDisplayFlow: Flow<FlashcardDisplaySettings> = context.dataStore.data.map { prefs ->
        FlashcardDisplaySettings(
            englishSp = prefs[Keys.flashcardEnglishSp] ?: 32,
            russianSp = prefs[Keys.flashcardRussianSp] ?: 22,
            englishColorArgb = prefs[Keys.flashcardEnglishColor]
                ?: FlashcardDisplaySettings().englishColorArgb,
            russianColorArgb = prefs[Keys.flashcardRussianColor]
                ?: FlashcardDisplaySettings().russianColorArgb,
            backgroundArgb = prefs[Keys.flashcardBackground]
                ?: FlashcardDisplaySettings().backgroundArgb,
            blankAfterSeconds = prefs[Keys.flashcardBlankAfterSec] ?: 60,
            speechPauseSeconds = prefs[Keys.flashcardSpeechPauseSec] ?: 3,
            englishTopPaddingDp = prefs[Keys.flashcardEnTopPad] ?: 8,
            enRuGapDp = prefs[Keys.flashcardEnRuGap] ?: 24,
            russianSmallerPercent = prefs[Keys.flashcardRuSmallerPct] ?: 31,
        )
    }

    suspend fun saveFlashcardDisplay(settings: FlashcardDisplaySettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.flashcardEnglishSp] = settings.englishSp.coerceIn(18, 72)
            prefs[Keys.flashcardRussianSp] = settings.russianSp.coerceIn(14, 48)
            prefs[Keys.flashcardEnglishColor] = settings.englishColorArgb
            prefs[Keys.flashcardRussianColor] = settings.russianColorArgb
            prefs[Keys.flashcardBackground] = settings.backgroundArgb
            prefs[Keys.flashcardBlankAfterSec] = settings.blankAfterSeconds.coerceIn(5, 300)
            prefs[Keys.flashcardSpeechPauseSec] = settings.speechPauseSeconds.coerceIn(0, 15)
            prefs[Keys.flashcardEnTopPad] = settings.englishTopPaddingDp.coerceIn(0, 160)
            prefs[Keys.flashcardEnRuGap] = settings.enRuGapDp.coerceIn(0, 120)
            prefs[Keys.flashcardRuSmallerPct] = settings.russianSmallerPercent.coerceIn(10, 55)
        }
    }

    suspend fun saveLocalTranscription(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.localTranscription] = enabled
        }
    }

    val ttsFavoritesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.ttsFavoriteVoices] ?: emptySet()
    }

    suspend fun saveTtsFavorites(names: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ttsFavoriteVoices] = names
        }
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
