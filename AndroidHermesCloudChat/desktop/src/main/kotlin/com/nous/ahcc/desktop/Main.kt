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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Checkbox
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
import com.nous.ahcc.desktop.config.DesktopUiSettings
import com.nous.ahcc.desktop.config.HermesConfig
import com.nous.ahcc.desktop.media.DesktopMediaEnvelope
import com.nous.ahcc.desktop.model.ChatMessage
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.MessageRole
import com.nous.ahcc.desktop.net.AhccSupabaseClient
import com.nous.ahcc.desktop.net.HermesHttpSseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

fun main() {
    // DirectX12 often fails on older/Intel GPUs; prefer OpenGL unless overridden.
    if (System.getProperty("skiko.renderApi").isNullOrBlank()) {
        System.setProperty("skiko.renderApi", "OPENGL")
    }
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        error.printStackTrace()
        try {
            javax.swing.SwingUtilities.invokeLater {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    error.message ?: error.toString(),
                    "AHCC Desktop crashed",
                    javax.swing.JOptionPane.ERROR_MESSAGE
                )
            }
        } catch (_: Exception) {
            // ignore UI failures during crash reporting
        }
    }

    application {
    val client = remember { HermesHttpSseClient() }
    Window(
        onCloseRequest = {
            client.disconnect()
            exitApplication()
        },
        title = "AHCC Desktop v${AppVersion.LABEL}",
        state = rememberWindowState(size = DpSize(920.dp, 720.dp))
    ) {
        val colors = darkColorScheme(
            primary = Color(0xFF1FA6A0),
            onPrimary = Color(0xFFE8F4F3),
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
                sessionId = DesktopUiSettings.sessionId.ifBlank { HermesConfig.SESSION_ID },
                host = HermesConfig.HOST,
                port = HermesConfig.PORT,
                useTls = HermesConfig.USE_TLS,
                agentApiBaseUrl = HermesConfig.AGENT_API_BASE_URL,
                supabaseUrl = DesktopUiSettings.supabaseUrl,
                supabaseAnonKey = DesktopUiSettings.supabaseAnonKey,
            )
        )
    }
    var draft by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var showSettings by remember { mutableStateOf(false) }
    var startOnLaunch by remember { mutableStateOf(DesktopUiSettings.startOnLaunch) }
    val listState = rememberLazyListState()

    fun persistConfig(c: ConnectionConfig) {
        DesktopUiSettings.persistConnection(
            sessionId = c.sessionId,
            supabaseUrl = c.supabaseUrl,
            supabaseAnonKey = c.supabaseAnonKey,
            startOnLaunch = startOnLaunch,
        )
    }

    fun supabase() = AhccSupabaseClient(config.supabaseUrl, config.supabaseAnonKey)

    fun historyFromSupabase(rows: List<com.nous.ahcc.desktop.net.AhccMessageRow>): List<ChatMessage> =
        rows.mapNotNull { row ->
            when {
                row.content.equals(AhccSupabaseClient.NEW_SESSION_CONTENT, ignoreCase = true) ->
                    ChatMessage(
                        id = row.id ?: UUID.randomUUID().toString(),
                        role = MessageRole.System,
                        content = "Session ${row.sessionId}"
                    )
                AhccSupabaseClient.parseFileMarker(row.content) != null -> {
                    val ref = AhccSupabaseClient.parseFileMarker(row.content)!!
                    ChatMessage(
                        id = row.id ?: UUID.randomUUID().toString(),
                        role = if (row.senderName.equals(AhccSupabaseClient.SENDER_HERMES, ignoreCase = true))
                            MessageRole.Assistant else MessageRole.User,
                        content = "[file] ${ref.second}"
                    )
                }
                row.senderName.equals(AhccSupabaseClient.SENDER_HERMES, ignoreCase = true) ->
                    ChatMessage(
                        id = row.id ?: UUID.randomUUID().toString(),
                        role = MessageRole.Assistant,
                        content = row.content
                    )
                else ->
                    ChatMessage(
                        id = row.id ?: UUID.randomUUID().toString(),
                        role = MessageRole.User,
                        content = row.content
                    )
            }
        }

    fun connectWithSession(forceNew: Boolean) {
        scope.launch {
            messages = emptyList()
            try {
                val sb = supabase()
                val resolved = withContext(Dispatchers.IO) {
                    sb.resolveOrCreateSession(
                        forceNew = forceNew,
                        preferredId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                    )
                }
                val next = config.copy(sessionId = resolved)
                config = next
                persistConfig(next)
                if (sb.isConfigured) {
                    val rows = withContext(Dispatchers.IO) { sb.fetchSessionMessages(resolved) }
                    messages = historyFromSupabase(rows)
                }
                client.connect(next)
            } catch (e: Exception) {
                messages = listOf(
                    ChatMessage(
                        UUID.randomUUID().toString(),
                        MessageRole.System,
                        e.message ?: "Connect / Supabase failed"
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (startOnLaunch && connection == ConnectionState.Disconnected) {
            connectWithSession(forceNew = false)
        }
    }

    LaunchedEffect(Unit) {
        client.responses.collect { response ->
            when (response.event) {
                "history" -> {
                    // Prefer Supabase history already loaded; only fill if empty.
                    if (messages.isNotEmpty()) return@collect
                    val items = response.history.orEmpty()
                    messages = items.mapNotNull { item ->
                        val role = when (item.role.lowercase()) {
                            "user" -> MessageRole.User
                            "assistant" -> MessageRole.Assistant
                            "system" -> MessageRole.System
                            else -> return@mapNotNull null
                        }
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = role,
                            content = item.content
                        )
                    }
                    response.content?.takeIf { it.isNotBlank() && items.isEmpty() }?.let { note ->
                        messages = messages + ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.System,
                            content = note
                        )
                    }
                }
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
                    val finalText = messages.lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()
                    messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
                    if (finalText.isNotBlank()) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    supabase().insert(
                                        sessionId = config.sessionId,
                                        senderName = AhccSupabaseClient.SENDER_HERMES,
                                        content = finalText,
                                        recipientName = AhccSupabaseClient.SENDER_DESKTOP,
                                    )
                                }
                            }
                        }
                    }
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
                withContext(Dispatchers.IO) {
                    supabase().insert(
                        sessionId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                        content = text,
                        recipientName = AhccSupabaseClient.SENDER_HERMES,
                    )
                }
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

    fun pickAndSendFile() {
        if (connection != ConnectionState.Connected) return
        val dialog = FileDialog(Frame(), "Attach file", FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return
        val fileName = dialog.file ?: return
        val file = File(dir, fileName)
        if (!file.isFile) return
        scope.launch {
            try {
                val lower = fileName.lowercase()
                val isTextShare = lower.endsWith(".md") || lower.endsWith(".markdown") ||
                    lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv")
                if (isTextShare && supabase().isConfigured) {
                    val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                    val mime = when {
                        lower.endsWith(".md") || lower.endsWith(".markdown") -> "text/markdown"
                        lower.endsWith(".json") -> "application/json"
                        lower.endsWith(".csv") -> "text/csv"
                        else -> "text/plain"
                    }
                    val marker = withContext(Dispatchers.IO) {
                        supabase().uploadTextFile(
                            sessionId = config.sessionId,
                            senderName = AhccSupabaseClient.SENDER_DESKTOP,
                            fileName = fileName,
                            text = text,
                            mime = mime,
                            recipientName = AhccSupabaseClient.SENDER_ANDROID,
                        )
                    }
                    val label = AhccSupabaseClient.parseFileMarker(marker)?.second ?: fileName
                    messages = messages + ChatMessage(
                        UUID.randomUUID().toString(),
                        MessageRole.User,
                        "[file] $label"
                    )
                    // Also notify Hermes briefly (optional chat context).
                    client.sendMessage("Shared file via Supabase: $label")
                    return@launch
                }

                val attachment = withContext(Dispatchers.IO) {
                    DesktopMediaEnvelope.fromFile(file)
                }
                val caption = draft.trim().ifBlank { null }
                draft = ""
                val envelope = DesktopMediaEnvelope.buildText(caption, attachment, config.sessionId)
                val placeholder = DesktopMediaEnvelope.placeholder(attachment, caption)
                messages = messages +
                    ChatMessage(UUID.randomUUID().toString(), MessageRole.User, placeholder) +
                    ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", true)
                withContext(Dispatchers.IO) {
                    supabase().insert(
                        sessionId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                        content = placeholder,
                        recipientName = AhccSupabaseClient.SENDER_HERMES,
                    )
                }
                client.sendMedia(envelope, placeholder)
            } catch (e: Exception) {
                messages = messages + ChatMessage(
                    UUID.randomUUID().toString(),
                    MessageRole.System,
                    e.message ?: "file send failed"
                )
            }
        }
    }

    LayoutColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LayoutRow(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LayoutColumn(modifier = Modifier.weight(1f)) {
                Text("AHCC Desktop v${AppVersion.LABEL}", style = MaterialTheme.typography.headlineMedium)
                LayoutRow(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ConnectionIndicator(connection)
                    Text(
                        "${connectionLabel(connection)} · HTTP · ${config.model}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                lastError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = {
                client.clearHistory()
                messages = emptyList()
            }) {
                Text("Clear", color = MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { connectWithSession(forceNew = true) }) {
                Text("New session", color = MaterialTheme.colorScheme.onSurface)
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(
                    if (showSettings) "Chat" else "Settings",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = {
                if (connection == ConnectionState.Connected || connection == ConnectionState.Connecting) {
                    client.disconnect()
                    messages = emptyList()
                } else {
                    connectWithSession(forceNew = false)
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
            SettingsPanel(
                config = config,
                startOnLaunch = startOnLaunch,
                onChange = {
                    config = it
                    persistConfig(it)
                },
                onStartOnLaunchChange = {
                    startOnLaunch = it
                    DesktopUiSettings.startOnLaunch = it
                }
            )
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
                            "Desktop client · Nous Inference HTTP SSE.\nEnter — send, Shift+Enter — newline.\nPaperclip — file via AHCC_MEDIA_V1.",
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
                IconButton(
                    onClick = { pickAndSendFile() },
                    enabled = connection == ConnectionState.Connected
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
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
private fun ConnectionIndicator(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.Connected -> Color(0xFF22C55E)
        ConnectionState.Connecting -> Color(0xFFFBBF24)
        ConnectionState.Error -> Color(0xFFEF4444)
        ConnectionState.Disconnected -> Color(0xFF64748B)
    }
    LayoutBox(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Error -> "Error"
    ConnectionState.Disconnected -> "Disconnected"
}

@Composable
private fun SettingsPanel(
    config: ConnectionConfig,
    startOnLaunch: Boolean,
    onChange: (ConnectionConfig) -> Unit,
    onStartOnLaunchChange: (Boolean) -> Unit,
) {
    LayoutColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Connection", style = MaterialTheme.typography.titleLarge)
        LayoutRow(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = startOnLaunch,
                onCheckedChange = onStartOnLaunchChange
            )
            Text("Start on launch", style = MaterialTheme.typography.bodyLarge)
        }
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
            label = { Text("Session ID (auto from Supabase last row)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.supabaseUrl,
            onValueChange = { onChange(config.copy(supabaseUrl = it)) },
            label = { Text("Supabase URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.supabaseAnonKey,
            onValueChange = { onChange(config.copy(supabaseAnonKey = it)) },
            label = { Text("Supabase anon key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.agentApiBaseUrl,
            onValueChange = { onChange(config.copy(agentApiBaseUrl = it)) },
            label = { Text("Agent API base URL (session history; empty = auto)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = config.host,
            onValueChange = { onChange(config.copy(host = it)) },
            label = { Text("Agent host (for history auto-detect)") },
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
