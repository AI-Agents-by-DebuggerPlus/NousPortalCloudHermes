package com.nous.ahcc.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.foundation.layout.Column as LayoutColumn
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nous.ahcc.desktop.config.HermesConfig
import com.nous.ahcc.desktop.model.ChatMessage
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.MessageRole
import com.nous.ahcc.desktop.net.HermesHttpSseClient
import kotlinx.coroutines.launch
import java.util.UUID

fun main() = application {
    val client = remember { HermesHttpSseClient() }
    Window(
        onCloseRequest = {
            client.disconnect()
            exitApplication()
        },
        title = "AHCC Desktop — Hermes Cloud Chat",
        state = rememberWindowState(size = DpSize(920.dp, 720.dp))
    ) {
        val colors = darkColorScheme(
            primary = Color(0xFF1FA6A0),
            background = Color(0xFF0B1F2A),
            surface = Color(0xFF123D45),
            onBackground = Color(0xFFE8F4F3),
            onSurface = Color(0xFFE8F4F3),
            surfaceVariant = Color(0xFF1A4550),
            onSurfaceVariant = Color(0xFF8AA8A6)
        )
        MaterialTheme(
            colorScheme = colors,
            content = {
                M3Surface(modifier = Modifier.fillMaxSize()) {
                    DesktopChatApp(client)
                }
            }
        )
    }
}

@Composable
private fun DesktopChatApp(client: HermesHttpSseClient) {
    val scope = rememberCoroutineScope()
    val connection by client.connectionState.collectAsState()
    val lastError by client.lastError.collectAsState()

    var config by remember {
        mutableStateOf(
            ConnectionConfig(
                apiKey = HermesConfig.API_KEY,
                model = HermesConfig.MODEL,
                inferenceBaseUrl = HermesConfig.INFERENCE_BASE_URL,
                sessionId = HermesConfig.SESSION_ID
            )
        )
    }
    var draft by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        client.responses.collect { response ->
            when (response.event) {
                "token" -> {
                    val delta = response.delta.orEmpty()
                    if (delta.isEmpty()) return@collect
                    messages = messages.toMutableList().also { list ->
                        val idx = list.indexOfLast { it.role == MessageRole.Assistant && it.isStreaming }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(content = list[idx].content + delta)
                        } else {
                            list += ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, delta, true)
                        }
                    }
                }
                "done" -> {
                    messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                }
                "error" -> {
                    messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it } +
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.System, response.error ?: "Error")
                }
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty() || connection != ConnectionState.Connected) return
        draft = ""
        messages = messages +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.User, text) +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", true)
        scope.launch {
            try {
                client.sendMessage(text)
            } catch (e: Exception) {
                messages = messages.map {
                    if (it.isStreaming) {
                        it.copy(
                            content = e.message ?: "send failed",
                            isStreaming = false,
                            role = MessageRole.System
                        )
                    } else it
                }
            }
        }
    }

    LayoutColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LayoutRow(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LayoutColumn(modifier = Modifier.weight(1f)) {
                Text("AHCC Desktop", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${connection.name} · HTTP · ${config.model}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = {
                client.clearHistory()
                messages = emptyList()
            }) { Text("Clear") }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(if (showSettings) "Chat" else "Settings")
            }
            IconButton(onClick = {
                if (connection == ConnectionState.Connected || connection == ConnectionState.Connecting) {
                    client.disconnect()
                } else {
                    client.connect(config)
                }
            }) {
                Icon(
                    if (connection == ConnectionState.Connected) Icons.Default.LinkOff else Icons.Default.Link,
                    contentDescription = "Toggle connection",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        LayoutSpacer(modifier = Modifier.height(12.dp))

        if (showSettings) {
            SettingsPanel(config) { config = it }
        } else {
            if (messages.isEmpty()) {
                LayoutBox(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    LayoutColumn {
                        Text("Android Hermes Cloud Chat", style = MaterialTheme.typography.displaySmall)
                        LayoutSpacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Desktop client · Nous Inference HTTP SSE.\nEnter — send, Shift+Enter — newline.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
                }
            }

            LayoutSpacer(modifier = Modifier.height(10.dp))
            LayoutRow(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                if (event.isShiftPressed) false
                                else {
                                    send()
                                    true
                                }
                            } else false
                        },
                    enabled = connection == ConnectionState.Connected,
                    placeholder = { Text("Message Hermes…") },
                    maxLines = 8,
                    shape = RoundedCornerShape(14.dp)
                )
                FilledIconButton(
                    onClick = { send() },
                    enabled = connection == ConnectionState.Connected && draft.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(config: ConnectionConfig, onChange: (ConnectionConfig) -> Unit) {
    LayoutColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Connection", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = config.inferenceBaseUrl,
            onValueChange = { onChange(config.copy(inferenceBaseUrl = it)) },
            label = { Text("Inference base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.model,
            onValueChange = { onChange(config.copy(model = it)) },
            label = { Text("Model (Inference API only; agent has its own server model)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { onChange(config.copy(apiKey = it)) },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.sessionId,
            onValueChange = { onChange(config.copy(sessionId = it)) },
            label = { Text("Session ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.User
    LayoutBox(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        LayoutColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when (message.role) {
                        MessageRole.User -> MaterialTheme.colorScheme.primary
                        MessageRole.System -> MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                when (message.role) {
                    MessageRole.User -> "You"
                    MessageRole.Assistant -> if (message.isStreaming) "Hermes · streaming" else "Hermes"
                    MessageRole.System -> "System"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            LayoutSpacer(modifier = Modifier.height(4.dp))
            Text(message.content.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
