package com.nous.ahcc.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.config.HermesConfig
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.TransportMode
import com.nous.ahcc.presentation.chat.HermesChatViewModel

@Composable
fun SettingsScreen(
    viewModel: HermesChatViewModel,
    onBack: () -> Unit,
    onOpenBluetoothTest: () -> Unit = {},
    onOpenVoiceTest: () -> Unit = {},
    onOpenTelegramLogin: () -> Unit = {},
    onOpenTtsVoices: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var host by remember(state.config.host) { mutableStateOf(state.config.host) }
    var port by remember(state.config.port) { mutableStateOf(state.config.port.toString()) }
    var apiKey by remember(state.config.apiKey) { mutableStateOf(state.config.apiKey) }
    var sessionId by remember(state.config.sessionId) { mutableStateOf(state.config.sessionId) }
    var useTls by remember(state.config.useTls) { mutableStateOf(state.config.useTls) }
    var keepAlive by remember(state.config.keepAliveInBackground) {
        mutableStateOf(state.config.keepAliveInBackground)
    }
    var agentApiBaseUrl by remember(state.config.agentApiBaseUrl) {
        mutableStateOf(state.config.agentApiBaseUrl)
    }
    var supabaseUrl by remember(state.config.supabaseUrl) {
        mutableStateOf(state.config.supabaseUrl)
    }
    var supabaseAnonKey by remember(state.config.supabaseAnonKey) {
        mutableStateOf(state.config.supabaseAnonKey)
    }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Connection",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Чат идёт через Telegram Bot API (getMe / sendMessage / sendAudio).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key (Bearer)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                }
            )
            OutlinedTextField(
                value = sessionId,
                onValueChange = { sessionId = it },
                label = { Text("Session ID (auto from Supabase last row)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = supabaseUrl,
                onValueChange = { supabaseUrl = it },
                label = { Text("Supabase URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = supabaseAnonKey,
                onValueChange = { supabaseAnonKey = it },
                label = { Text("Supabase anon key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                }
            )
            OutlinedTextField(
                value = agentApiBaseUrl,
                onValueChange = { agentApiBaseUrl = it },
                label = { Text("Agent API base URL (history; empty = auto)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("WS Host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("WS Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            SettingToggle(
                title = "Use WSS (TLS)",
                checked = useTls,
                onCheckedChange = { useTls = it }
            )
            SettingToggle(
                title = "Keep alive in background (Foreground Service)",
                checked = keepAlive,
                onCheckedChange = { keepAlive = it }
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onOpenBluetoothTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Тест кнопок Bluetooth-гарнитуры")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenTelegramLogin,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Вход в Telegram (пользователь)")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenTtsVoices,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Голоса озвучки")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenVoiceTest,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Тест голоса (Start / Play / Send)")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val parsedPort = port.toIntOrNull() ?: HermesConfig.PORT
                viewModel.updateConfig(
                    ConnectionConfig(
                        host = host.trim(),
                        port = parsedPort,
                        apiKey = apiKey,
                        sessionId = sessionId.trim().ifBlank { HermesConfig.SESSION_ID },
                        useTls = useTls,
                        keepAliveInBackground = keepAlive,
                        transport = TransportMode.WebSocket,
                        model = state.config.model,
                        inferenceBaseUrl = state.config.inferenceBaseUrl,
                        agentApiBaseUrl = agentApiBaseUrl.trim(),
                        supabaseUrl = supabaseUrl.trim(),
                        supabaseAnonKey = supabaseAnonKey.trim(),
                        telegramBotToken = state.config.telegramBotToken,
                        telegramChatId = state.config.telegramChatId
                    )
                )
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
