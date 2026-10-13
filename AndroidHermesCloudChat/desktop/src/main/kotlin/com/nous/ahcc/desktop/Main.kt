package com.nous.ahcc.desktop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.foundation.layout.Column as LayoutColumn
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.nous.ahcc.desktop.log.DesktopChatLog
import com.nous.ahcc.desktop.media.DesktopMediaEnvelope
import com.nous.ahcc.desktop.model.Addressee
import com.nous.ahcc.desktop.model.ChatChannel
import com.nous.ahcc.desktop.model.ChatMessage
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.MessageRole
import com.nous.ahcc.desktop.model.TelegramInlineAction
import com.nous.ahcc.desktop.model.TelegramInlineButton
import com.nous.ahcc.desktop.model.TelegramInlineKeyboard
import com.nous.ahcc.desktop.net.AhccSupabaseClient
import com.nous.ahcc.desktop.net.DesktopChatGateway
import com.nous.ahcc.desktop.telegram.DesktopTelegramAuth
import com.nous.ahcc.desktop.ui.AhccTitleBar
import com.nous.ahcc.desktop.ui.MessageBody
import com.nous.ahcc.desktop.ui.TgTheme
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
    val client = remember { DesktopChatGateway() }
    val windowState = rememberWindowState(size = DpSize(920.dp, 720.dp))
    val closeApp = {
        client.disconnect()
        exitApplication()
    }
    Window(
        onCloseRequest = closeApp,
        title = "AHCC Desktop v${AppVersion.LABEL}",
        state = windowState,
        undecorated = true,
    ) {
        MaterialTheme(colorScheme = TgTheme.colorScheme()) {
            LayoutColumn(modifier = Modifier.fillMaxSize().background(TgTheme.ChatBg)) {
                AhccTitleBar(
                    title = "AHCC Desktop v${AppVersion.LABEL}",
                    windowState = windowState,
                    onClose = closeApp,
                )
                M3Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = TgTheme.ChatBg,
                ) {
                    DesktopChatApp(client)
                }
            }
        }
    }
    }
}

@Composable
private fun DesktopChatApp(client: DesktopChatGateway) {
    val scope = rememberCoroutineScope()
    val connection by client.connectionState.collectAsState()
    val lastError by client.lastError.collectAsState()
    val telegramAuth by client.telegram.auth.collectAsState()

    var config by remember { mutableStateOf(DesktopUiSettings.loadConnection()) }
    var draft by remember { mutableStateOf("") }
    var pendingAttach by remember { mutableStateOf<File?>(null) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var showSettings by remember { mutableStateOf(false) }
    var startOnLaunch by remember { mutableStateOf(DesktopUiSettings.startOnLaunch) }
    var addressees by remember {
        mutableStateOf(
            DesktopUiSettings.addresseeCatalog.ifEmpty { Addressee.defaultCatalog }
        )
    }
    var defaultAddressee by remember {
        mutableStateOf(Addressee.resolve(addressees, DesktopUiSettings.defaultAddresseeId))
    }
    var currentAddressee by remember {
        mutableStateOf(Addressee.resolve(addressees, DesktopUiSettings.currentAddresseeId))
    }
    var showAddReceiver by remember { mutableStateOf(false) }
    var waitingReply by remember { mutableStateOf(false) }
    var lastReplyComplete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun persistConfig(c: ConnectionConfig) {
        DesktopUiSettings.persistConnection(c, startOnLaunch)
    }

    fun persistAddressees(
        catalog: List<Addressee> = addressees,
        defaultId: String = defaultAddressee.id,
        currentId: String = currentAddressee.id,
    ) {
        DesktopUiSettings.persistAddressees(catalog, defaultId, currentId)
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
        client.telegram.setIdleAppend { chunk, keyboard ->
            messages = messages.toMutableList().also { list ->
                val idx = list.indexOfLast { it.role == MessageRole.Assistant && it.isComplete }
                if (idx >= 0) {
                    val prev = list[idx]
                    list[idx] = prev.copy(
                        content = (prev.content + "\n\n" + chunk).trim(),
                        inlineKeyboard = keyboard ?: prev.inlineKeyboard,
                    )
                }
            }
            lastReplyComplete = true
            DesktopChatLog.inbound(
                sessionId = config.sessionId,
                addresseeId = currentAddressee.id,
                role = "assistant",
                text = chunk,
                note = "idle-append",
            )
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
                "typing" -> {
                    waitingReply = true
                    lastReplyComplete = false
                    if (messages.none { it.role == MessageRole.Assistant && it.isStreaming }) {
                        messages = messages + ChatMessage(
                            UUID.randomUUID().toString(),
                            MessageRole.Assistant,
                            "",
                            isStreaming = true,
                        )
                    }
                }
                "partial" -> {
                    waitingReply = true
                    lastReplyComplete = false
                    val text = response.content.orEmpty()
                    messages = messages.toMutableList().also { list ->
                        val idx = list.indexOfLast { it.role == MessageRole.Assistant && it.isStreaming }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(content = text)
                        } else {
                            list += ChatMessage(
                                UUID.randomUUID().toString(),
                                MessageRole.Assistant,
                                text,
                                isStreaming = true,
                            )
                        }
                    }
                }
                "done" -> {
                    waitingReply = false
                    lastReplyComplete = true
                    val streamed = messages.lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()
                    val finalText = response.content?.takeIf { it.isNotBlank() } ?: streamed
                    val keyboard = response.inlineKeyboard
                    messages = messages.map {
                        if (it.isStreaming) {
                            it.copy(
                                content = finalText.ifBlank { it.content },
                                isStreaming = false,
                                isComplete = true,
                                inlineKeyboard = keyboard,
                            )
                        } else it
                    }
                    if (finalText.isNotBlank()) {
                        DesktopChatLog.inbound(
                            sessionId = config.sessionId,
                            addresseeId = currentAddressee.id,
                            role = "assistant",
                            text = finalText,
                        )
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
                    waitingReply = false
                    val err = response.error ?: "Error"
                    DesktopChatLog.inbound(
                        sessionId = config.sessionId,
                        addresseeId = currentAddressee.id,
                        role = "error",
                        text = err,
                    )
                    messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it } +
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.System, err)
                }
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun sendTextOnly(text: String) {
        val wire = Addressee.wireText(text, currentAddressee)
        val endSession = Addressee.isSessionEndCommand(text)
        DesktopChatLog.out(
            sessionId = config.sessionId,
            addresseeId = currentAddressee.id,
            text = wire,
        )
        waitingReply = true
        lastReplyComplete = false
        messages = messages +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.User, text) +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", isStreaming = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    supabase().insert(
                        sessionId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                        content = wire,
                        recipientName = AhccSupabaseClient.SENDER_HERMES,
                    )
                }
                client.sendMessage(wire)
                if (endSession && currentAddressee.id != defaultAddressee.id) {
                    currentAddressee = defaultAddressee
                    persistAddressees(currentId = defaultAddressee.id)
                }
            } catch (e: Exception) {
                waitingReply = false
                val err = e.message ?: "send failed"
                DesktopChatLog.system(config.sessionId, err)
                messages = messages.map {
                    if (it.isStreaming) {
                        it.copy(
                            content = err,
                            isStreaming = false,
                            role = MessageRole.System
                        )
                    } else it
                }
            }
        }
    }

    fun onInlineButton(messageId: String, action: TelegramInlineAction) {
        if (waitingReply) return
        val msg = messages.find { it.id == messageId } ?: return
        val kb = msg.inlineKeyboard
        val label = kb?.rows?.flatten()
            ?.firstOrNull { it.action == action }
            ?.text
            ?: "✓"
        messages = messages.map {
            if (it.id == messageId) it.copy(inlineKeyboard = null) else it
        }
        waitingReply = true
        lastReplyComplete = false
        messages = messages +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.User, label) +
            ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", isStreaming = true)
        DesktopChatLog.out(
            sessionId = config.sessionId,
            addresseeId = currentAddressee.id,
            text = label,
            note = "inline",
        )
        scope.launch {
            try {
                client.handleInlineAction(action, kb)
            } catch (e: Exception) {
                waitingReply = false
                val err = e.message ?: "button failed"
                DesktopChatLog.system(config.sessionId, err)
                messages = messages.map {
                    if (it.isStreaming) it.copy(content = err, isStreaming = false, role = MessageRole.System)
                    else it
                }
            }
        }
    }

    fun sendPendingFile(file: File, caption: String?) {
        scope.launch {
            try {
                val fileName = file.name
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
                    val notifyBody = buildString {
                        if (!caption.isNullOrBlank()) append(caption).append('\n')
                        append("Shared file via Supabase: $label")
                    }
                    val notify = Addressee.wireText(notifyBody, currentAddressee)
                    DesktopChatLog.out(
                        sessionId = config.sessionId,
                        addresseeId = currentAddressee.id,
                        text = notify,
                        note = "file=$label",
                    )
                    messages = messages +
                        ChatMessage(
                            UUID.randomUUID().toString(),
                            MessageRole.User,
                            buildString {
                                if (!caption.isNullOrBlank()) append(caption).append('\n')
                                append("[file] $label")
                            },
                        ) +
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", true)
                    client.sendMessage(notify)
                    return@launch
                }

                val attachment = withContext(Dispatchers.IO) {
                    DesktopMediaEnvelope.fromFile(file)
                }
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
                val mediaHint = Addressee.wireText(placeholder, currentAddressee)
                DesktopChatLog.out(
                    sessionId = config.sessionId,
                    addresseeId = currentAddressee.id,
                    text = mediaHint,
                    note = "media",
                )
                client.sendMedia(envelope, mediaHint)
            } catch (e: Exception) {
                val err = e.message ?: "file send failed"
                DesktopChatLog.system(config.sessionId, err)
                messages = messages + ChatMessage(
                    UUID.randomUUID().toString(),
                    MessageRole.System,
                    err
                )
            }
        }
    }

    fun send() {
        if (connection != ConnectionState.Connected) return
        val caption = draft.trim().ifBlank { null }
        val file = pendingAttach
        if (file != null) {
            if (!file.isFile) {
                pendingAttach = null
                return
            }
            draft = ""
            pendingAttach = null
            sendPendingFile(file, caption)
            return
        }
        val text = caption ?: return
        draft = ""
        sendTextOnly(text)
    }

    fun pickAttachment() {
        if (connection != ConnectionState.Connected) return
        val dialog = FileDialog(Frame(), "Attach file", FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return
        val fileName = dialog.file ?: return
        val file = File(dir, fileName)
        if (!file.isFile) return
        pendingAttach = file
    }

    if (showAddReceiver) {
        AddReceiverDialog(
            onDismiss = { showAddReceiver = false },
            onConfirm = { name, key ->
                val id = key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?: Addressee.newId(name, addressees)
                if (addressees.any { it.id.equals(id, ignoreCase = true) }) return@AddReceiverDialog
                val next = Addressee(id, name.trim())
                addressees = addressees + next
                currentAddressee = next
                persistAddressees(catalog = addressees, currentId = next.id)
            },
        )
    }

    LayoutColumn(modifier = Modifier.fillMaxSize().background(TgTheme.ChatBg)) {
        LayoutRow(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(TgTheme.Panel)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            LayoutColumn(modifier = Modifier.weight(1f)) {
                LayoutRow(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionIndicator(connection)
                    Text(
                        when (config.channel) {
                            ChatChannel.Telegram ->
                                "${connectionLabel(connection)} · Telegram · @${config.telegramBotUsername.ifBlank { "bot" }}"
                            ChatChannel.HttpSse ->
                                "${connectionLabel(connection)} · HTTP · ${config.model}"
                        },
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                lastError?.let {
                    Text(it, color = TgTheme.Danger, style = MaterialTheme.typography.labelSmall)
                }
                if (config.channel == ChatChannel.Telegram && telegramAuth !is DesktopTelegramAuth.Ready) {
                    Text(
                        telegramAuthLabel(telegramAuth),
                        color = TgTheme.Warn,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                when {
                    waitingReply -> Text(
                        "Hermes is typing…",
                        color = TgTheme.Warn,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    lastReplyComplete -> Text(
                        "Reply received ✓",
                        color = TgTheme.Ok,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!showSettings) {
                AddresseeDropdown(
                    addressees = addressees,
                    selected = currentAddressee,
                    defaultAddressee = defaultAddressee,
                    onSelect = {
                        currentAddressee = it
                        persistAddressees(currentId = it.id)
                    },
                    compact = true,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            TextButton(onClick = {
                client.clearHistory()
                messages = emptyList()
            }) {
                Text("Clear", color = TgTheme.Text)
            }
            TextButton(onClick = { connectWithSession(forceNew = true) }) {
                Text("New session", color = TgTheme.Text)
            }
            TextButton(onClick = { showSettings = !showSettings }) {
                Text(
                    if (showSettings) "Chat" else "Settings",
                    color = TgTheme.Text,
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
                    tint = TgTheme.Accent,
                )
            }
        }

        if (showSettings) {
            SettingsPanel(
                config = config,
                startOnLaunch = startOnLaunch,
                telegramAuth = telegramAuth,
                addressees = addressees,
                currentAddressee = currentAddressee,
                defaultAddressee = defaultAddressee,
                onChange = {
                    config = it
                    persistConfig(it)
                },
                onStartOnLaunchChange = {
                    startOnLaunch = it
                    DesktopUiSettings.startOnLaunch = it
                },
                onSelectAddressee = {
                    currentAddressee = it
                    persistAddressees(currentId = it.id)
                },
                onAddAddressee = { showAddReceiver = true },
                onDeleteAddressee = {
                    if (currentAddressee.id == Addressee.MAIN_AGENT_ID) return@SettingsPanel
                    val next = addressees.filter { it.id != currentAddressee.id }
                    addressees = next
                    val resolvedDefault = Addressee.resolve(next, defaultAddressee.id)
                    defaultAddressee = resolvedDefault
                    currentAddressee = resolvedDefault
                    persistAddressees(
                        catalog = next,
                        defaultId = resolvedDefault.id,
                        currentId = resolvedDefault.id,
                    )
                },
                onSetDefaultAddressee = {
                    defaultAddressee = currentAddressee
                    persistAddressees(defaultId = currentAddressee.id)
                },
                onTelegramPhone = { client.telegram.submitPhone(it) },
                onTelegramCode = { client.telegram.submitCode(it) },
                onTelegramPassword = { client.telegram.submitPassword(it) },
                onTelegramConnect = {
                    persistConfig(config)
                    client.connect(config)
                },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            )
        } else {
            if (messages.isEmpty()) {
                LayoutBox(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when (config.channel) {
                            ChatChannel.Telegram ->
                                "Telegram channel → Hermes bot + TO: addressees.\n" +
                                    "Settings → Credentials to sign in.\n" +
                                    "Enter — send · Paperclip — attach."
                            ChatChannel.HttpSse ->
                                "HTTP SSE (Inference) — no server-side tools/routing.\n" +
                                    "Enter — send · Paperclip — attach."
                        },
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onInlineButton = { action -> onInlineButton(msg.id, action) },
                        )
                    }
                }
            }

            LayoutColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TgTheme.Panel)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                pendingAttach?.let { file ->
                    LayoutRow(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(TgTheme.InputField)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "📎 ${file.name}",
                            color = TgTheme.Text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Enter / Send →",
                            color = TgTheme.Secondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        IconButton(onClick = { pendingAttach = null }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove attachment",
                                tint = TgTheme.Secondary,
                            )
                        }
                    }
                    LayoutSpacer(modifier = Modifier.height(6.dp))
                }
                LayoutRow(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = { pickAttachment() },
                        enabled = connection == ConnectionState.Connected,
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Attach file",
                            tint = TgTheme.Secondary,
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
                        placeholder = {
                            Text(
                                when {
                                    pendingAttach != null -> "Caption for file (Enter to send)…"
                                    currentAddressee.id == Addressee.MAIN_AGENT_ID -> "Message MainAgent…"
                                    else -> "Message ${currentAddressee.displayName}…"
                                },
                                color = TgTheme.Secondary,
                            )
                        },
                        maxLines = 8,
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TgTheme.Text,
                            unfocusedTextColor = TgTheme.Text,
                            focusedBorderColor = TgTheme.AccentSoft,
                            unfocusedBorderColor = TgTheme.Border,
                            focusedContainerColor = TgTheme.InputField,
                            unfocusedContainerColor = TgTheme.InputField,
                            cursorColor = TgTheme.Accent,
                        ),
                    )
                    FilledIconButton(
                        onClick = { send() },
                        enabled = connection == ConnectionState.Connected &&
                            (draft.isNotBlank() || pendingAttach != null),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddresseeDropdown(
    addressees: List<Addressee>,
    selected: Addressee,
    defaultAddressee: Addressee,
    onSelect: (Addressee) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = if (compact) {
            modifier.widthIn(min = 120.dp, max = 180.dp)
        } else {
            modifier.fillMaxWidth().padding(bottom = 4.dp)
        },
    ) {
        val fieldLabel = if (compact) {
            selected.displayName
        } else if (selected.id == defaultAddressee.id) {
            "${selected.displayName} (default)"
        } else {
            selected.displayName
        }
        OutlinedTextField(
            value = fieldLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = if (compact) null else ({ Text("To") }),
            prefix = if (compact) {
                { Text("To ", color = TgTheme.Secondary, style = MaterialTheme.typography.labelSmall) }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = if (compact) {
                MaterialTheme.typography.labelLarge
            } else {
                MaterialTheme.typography.bodyLarge
            },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .then(if (compact) Modifier.fillMaxWidth().heightIn(max = 44.dp) else Modifier.fillMaxWidth()),
            shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TgTheme.Text,
                unfocusedTextColor = TgTheme.Text,
                focusedBorderColor = TgTheme.AccentSoft,
                unfocusedBorderColor = TgTheme.Border,
                focusedContainerColor = TgTheme.InputField,
                unfocusedContainerColor = TgTheme.InputField,
                focusedLabelColor = TgTheme.Secondary,
                unfocusedLabelColor = TgTheme.Secondary,
                focusedTrailingIconColor = TgTheme.Text,
                unfocusedTrailingIconColor = TgTheme.Secondary,
                focusedPrefixColor = TgTheme.Secondary,
                unfocusedPrefixColor = TgTheme.Secondary,
            ),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(TgTheme.Panel),
        ) {
            addressees.forEach { addressee ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                if (addressee.id == defaultAddressee.id) {
                                    "${addressee.displayName} (default)"
                                } else {
                                    addressee.displayName
                                },
                                color = TgTheme.Text,
                            )
                            if (addressee.id != Addressee.MAIN_AGENT_ID) {
                                Text(
                                    addressee.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TgTheme.Secondary,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(addressee)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun AddReceiverDialog(
    onDismiss: () -> Unit,
    onConfirm: (displayName: String, addressingKey: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TgTheme.Panel,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("Add new receiver") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = addReceiverFieldColors(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' } },
                    label = { Text("Addressing key (optional)") },
                    placeholder = { Text("english_tutor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = addReceiverFieldColors(),
                )
                Text(
                    "Sent as: TO: <key> on the first line.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(name, key.ifBlank { null })
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Add", color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun addReceiverFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = TgTheme.Border,
    focusedContainerColor = TgTheme.InputField,
    unfocusedContainerColor = TgTheme.InputField,
    focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

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

private fun telegramAuthLabel(auth: DesktopTelegramAuth): String = when (auth) {
    is DesktopTelegramAuth.Starting -> "Telegram: starting…"
    is DesktopTelegramAuth.NeedPhone -> "Telegram: phone required (Credentials)"
    is DesktopTelegramAuth.NeedCode -> "Telegram: enter code from SMS/Telegram"
    is DesktopTelegramAuth.NeedPassword -> "Telegram: 2FA password"
    is DesktopTelegramAuth.Ready -> "Telegram: ready"
    is DesktopTelegramAuth.Failed -> "Telegram: ${auth.message}"
}

@Composable
private fun SettingsPanel(
    config: ConnectionConfig,
    startOnLaunch: Boolean,
    telegramAuth: DesktopTelegramAuth,
    addressees: List<Addressee>,
    currentAddressee: Addressee,
    defaultAddressee: Addressee,
    onChange: (ConnectionConfig) -> Unit,
    onStartOnLaunchChange: (Boolean) -> Unit,
    onSelectAddressee: (Addressee) -> Unit,
    onAddAddressee: () -> Unit,
    onDeleteAddressee: () -> Unit,
    onSetDefaultAddressee: () -> Unit,
    onTelegramPhone: (String) -> Unit,
    onTelegramCode: (String) -> Unit,
    onTelegramPassword: (String) -> Unit,
    onTelegramConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(0) }
    var codeDraft by remember { mutableStateOf("") }
    var passwordDraft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LayoutColumn(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = TgTheme.Panel,
            contentColor = TgTheme.Text,
            edgePadding = 0.dp,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("Connection", color = TgTheme.Text) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("Credentials", color = TgTheme.Text) },
            )
            Tab(
                selected = tab == 2,
                onClick = { tab = 2 },
                text = { Text("Addressees", color = TgTheme.Text) },
            )
        }
        LayoutColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(top = 12.dp, end = 8.dp),
        ) {
            when (tab) {
                0 -> {
                    Text("Channel", style = MaterialTheme.typography.titleMedium, color = TgTheme.Text)
                    LayoutRow(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = config.channel == ChatChannel.Telegram,
                            onClick = { onChange(config.copy(channel = ChatChannel.Telegram)) },
                        )
                        Text("Telegram (default, same as AHCC)", modifier = Modifier.padding(end = 16.dp), color = TgTheme.Text)
                        RadioButton(
                            selected = config.channel == ChatChannel.HttpSse,
                            onClick = { onChange(config.copy(channel = ChatChannel.HttpSse)) },
                        )
                        Text("HTTP SSE (Inference)", color = TgTheme.Text)
                    }
                    LayoutRow(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = startOnLaunch, onCheckedChange = onStartOnLaunchChange)
                        Text("Start on launch", color = TgTheme.Text)
                    }
                    CompactField(
                        value = config.sessionId,
                        onValueChange = { onChange(config.copy(sessionId = it)) },
                        label = "Session ID",
                        width = 360.dp,
                    )
                    CompactField(
                        value = config.telegramBotUsername,
                        onValueChange = { onChange(config.copy(telegramBotUsername = it.trim().removePrefix("@"))) },
                        label = "Telegram bot username",
                        width = 280.dp,
                    )
                    CompactField(
                        value = config.inferenceBaseUrl,
                        onValueChange = { onChange(config.copy(inferenceBaseUrl = it)) },
                        label = "Inference base URL (HTTP only)",
                        width = 420.dp,
                    )
                    CompactField(
                        value = config.model,
                        onValueChange = { onChange(config.copy(model = it)) },
                        label = "Model (HTTP only)",
                        width = 280.dp,
                    )
                    CompactField(
                        value = config.host,
                        onValueChange = { onChange(config.copy(host = it)) },
                        label = "Agent host",
                        width = 420.dp,
                    )
                    CompactField(
                        value = config.agentApiBaseUrl,
                        onValueChange = { onChange(config.copy(agentApiBaseUrl = it)) },
                        label = "Agent API base (optional)",
                        width = 420.dp,
                    )
                    CompactField(
                        value = config.supabaseUrl,
                        onValueChange = { onChange(config.copy(supabaseUrl = it)) },
                        label = "Supabase URL",
                        width = 420.dp,
                    )
                }
                1 -> {
                    Text("Credentials", style = MaterialTheme.typography.titleMedium, color = TgTheme.Text)
                    Text(
                        "Secrets are stored in ~/.ahcc-desktop/ui.properties",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    CompactField(
                        value = config.apiKey,
                        onValueChange = { onChange(config.copy(apiKey = it)) },
                        label = "Nous API key",
                        width = 420.dp,
                        secret = true,
                    )
                    CompactField(
                        value = config.supabaseAnonKey,
                        onValueChange = { onChange(config.copy(supabaseAnonKey = it)) },
                        label = "Supabase anon key",
                        width = 420.dp,
                        secret = true,
                    )
                    CompactField(
                        value = config.telegramApiId.takeIf { it != 0 }?.toString().orEmpty(),
                        onValueChange = { onChange(config.copy(telegramApiId = it.toIntOrNull() ?: 0)) },
                        label = "Telegram api_id",
                        width = 160.dp,
                    )
                    CompactField(
                        value = config.telegramApiHash,
                        onValueChange = { onChange(config.copy(telegramApiHash = it)) },
                        label = "Telegram api_hash",
                        width = 320.dp,
                        secret = true,
                    )
                    CompactField(
                        value = config.telegramPhone,
                        onValueChange = {
                            onChange(config.copy(telegramPhone = it))
                            onTelegramPhone(it)
                        },
                        label = "Telegram phone (+…)",
                        width = 200.dp,
                    )
                    CompactField(
                        value = config.telegramBotToken,
                        onValueChange = { onChange(config.copy(telegramBotToken = it)) },
                        label = "Bot token (optional)",
                        width = 420.dp,
                        secret = true,
                    )
                    CompactField(
                        value = config.telegramChatId,
                        onValueChange = { onChange(config.copy(telegramChatId = it)) },
                        label = "Chat id (optional)",
                        width = 200.dp,
                    )
                    Text(
                        telegramAuthLabel(telegramAuth),
                        color = if (telegramAuth is DesktopTelegramAuth.Failed) TgTheme.Danger else TgTheme.Secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (telegramAuth is DesktopTelegramAuth.NeedCode) {
                        CompactField(
                            value = codeDraft,
                            onValueChange = { codeDraft = it.filter { ch -> ch.isDigit() } },
                            label = "Login code",
                            width = 160.dp,
                        )
                        OutlinedButton(
                            onClick = { onTelegramCode(codeDraft) },
                            enabled = codeDraft.isNotBlank(),
                        ) {
                            Text("Submit code", color = TgTheme.Text)
                        }
                        Text(
                            "Do not press Connect again — only Submit code. An old code may have expired: request a new one (restart Connect only if Failed).",
                            color = TgTheme.Secondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (telegramAuth is DesktopTelegramAuth.NeedPassword) {
                        CompactField(
                            value = passwordDraft,
                            onValueChange = { passwordDraft = it },
                            label = "2FA password",
                            width = 240.dp,
                            secret = true,
                        )
                        OutlinedButton(
                            onClick = { onTelegramPassword(passwordDraft) },
                            enabled = passwordDraft.isNotBlank(),
                        ) {
                            Text("Submit password", color = TgTheme.Text)
                        }
                    }
                    if (telegramAuth !is DesktopTelegramAuth.NeedCode &&
                        telegramAuth !is DesktopTelegramAuth.NeedPassword
                    ) {
                        OutlinedButton(onClick = onTelegramConnect) {
                            Text("Connect / login Telegram", color = TgTheme.Text)
                        }
                    }
                }
                else -> {
                    Text("Addressees", style = MaterialTheme.typography.titleMedium, color = TgTheme.Text)
                    Text(
                        "MainAgent sends without TO:. Others prefix TO: <key>.",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    AddresseeDropdown(
                        addressees = addressees,
                        selected = currentAddressee,
                        defaultAddressee = defaultAddressee,
                        onSelect = onSelectAddressee,
                    )
                    LayoutRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onDeleteAddressee,
                            enabled = currentAddressee.id != Addressee.MAIN_AGENT_ID,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Delete current", color = TgTheme.Text, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onAddAddressee,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Add new", color = TgTheme.Text, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onSetDefaultAddressee,
                            enabled = currentAddressee.id != defaultAddressee.id,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Set default", color = TgTheme.Text, maxLines = 1)
                        }
                    }
                }
            }
            LayoutSpacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    width: Dp,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier
            .widthIn(max = width)
            .fillMaxWidth(0.98f)
            .heightIn(max = 52.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TgTheme.Text,
            unfocusedTextColor = TgTheme.Text,
            focusedBorderColor = TgTheme.AccentSoft,
            unfocusedBorderColor = TgTheme.Border,
            focusedContainerColor = TgTheme.InputField,
            unfocusedContainerColor = TgTheme.InputField,
            focusedLabelColor = TgTheme.Secondary,
            unfocusedLabelColor = TgTheme.Secondary,
            cursorColor = TgTheme.Accent,
        ),
    )
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onInlineButton: (TelegramInlineAction) -> Unit = {},
) {
    val isUser = message.role == MessageRole.User
    val keyboard = message.inlineKeyboard
    val hasKeyboard = keyboard != null && !keyboard.isEmpty
    val bubbleBg = when (message.role) {
        MessageRole.User -> TgTheme.Outgoing
        MessageRole.System -> TgTheme.Danger.copy(alpha = 0.25f)
        else -> TgTheme.Incoming
    }
    val fg = TgTheme.Text
    LayoutBox(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        LayoutColumn(modifier = Modifier.widthIn(max = if (isUser) 420.dp else 560.dp)) {
            LayoutColumn(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 14.dp,
                            topEnd = 14.dp,
                            bottomStart = if (isUser) 14.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 14.dp,
                        )
                    )
                    .background(bubbleBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (!isUser) {
                    Text(
                        when (message.role) {
                            MessageRole.Assistant -> when {
                                message.isStreaming -> "Hermes · typing"
                                message.isComplete -> "Hermes · done"
                                else -> "Hermes"
                            }
                            MessageRole.System -> "System"
                            else -> "You"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TgTheme.Accent,
                    )
                    LayoutSpacer(modifier = Modifier.height(4.dp))
                }
                if (message.isStreaming && message.content.isBlank()) {
                    TypingDots(color = fg)
                } else if (message.content.isNotBlank()) {
                    MessageBody(text = message.content, color = fg)
                } else {
                    Text("…", color = TgTheme.Secondary)
                }
            }
            if (hasKeyboard && keyboard != null) {
                LayoutSpacer(modifier = Modifier.height(6.dp))
                InlineKeyboardBlock(
                    keyboard = keyboard,
                    enabled = !message.isStreaming,
                    onClick = onInlineButton,
                )
            }
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    LayoutRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("typing", color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            LayoutBox(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun InlineKeyboardBlock(
    keyboard: TelegramInlineKeyboard,
    enabled: Boolean,
    onClick: (TelegramInlineAction) -> Unit,
) {
    LayoutColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keyboard.rows.forEach { row ->
            LayoutRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { button ->
                    InlineButtonChip(
                        button = button,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(button.action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineButtonChip(
    button: TelegramInlineButton,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    LayoutBox(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(TgTheme.InputField)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = button.text,
            color = TgTheme.Text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
