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
                .ifBlank { HermesConfig.SUPABASE_ANON_KEY }
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
        }
    }
}
