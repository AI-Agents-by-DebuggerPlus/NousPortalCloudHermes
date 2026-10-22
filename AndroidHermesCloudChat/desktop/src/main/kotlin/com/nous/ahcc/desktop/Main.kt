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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nous.ahcc.desktop.config.DesktopUiSettings
import com.nous.ahcc.desktop.model.Addressee
import com.nous.ahcc.desktop.model.ChatChannel
import com.nous.ahcc.desktop.model.ChatMessage
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.MessageRole
import com.nous.ahcc.desktop.model.TelegramInlineAction
import com.nous.ahcc.desktop.model.TelegramInlineButton
import com.nous.ahcc.desktop.model.TelegramInlineKeyboard
import com.nous.ahcc.desktop.net.DesktopChatGateway
import com.nous.ahcc.desktop.telegram.DesktopTelegramAuth
import com.nous.ahcc.desktop.ui.AhccTitleBar
import com.nous.ahcc.desktop.ui.MessageBody
import com.nous.ahcc.desktop.ui.TgTheme
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

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
        val app = remember { AhccAppState(client) }
        LaunchedEffect(Unit) { app.start() }
        DesktopWorkspace(app)
    }
}

/**
 * Owns the window set: the main window (agent list + logs), one permanently
 * bound chat window per opened addressee, and transient inbound popups.
 */
@Composable
private fun ApplicationScope.DesktopWorkspace(app: AhccAppState) {
    val closeApp = {
        app.shutdown()
        exitApplication()
    }
    val mainWindowState = rememberWindowState(size = DpSize(1100.dp, 760.dp))
    Window(
        onCloseRequest = closeApp,
        title = "AHCC Desktop v${AppVersion.LABEL}",
        state = mainWindowState,
        undecorated = true,
    ) {
        MaterialTheme(colorScheme = TgTheme.colorScheme()) {
            LayoutColumn(modifier = Modifier.fillMaxSize().background(TgTheme.ChatBg)) {
                AhccTitleBar(
                    title = "AHCC Desktop v${AppVersion.LABEL}",
                    windowState = mainWindowState,
                    onClose = closeApp,
                    background = TgTheme.TitleBarMain,
                )
                M3Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = TgTheme.ChatBg,
                ) {
                    MainWindowContent(app)
                }
            }
        }
    }

    // One window per open addressee — never re-pointed at another agent.
    app.openAddresseeIds.toList().forEach { addresseeId ->
        val addressee = app.addresseeById(addresseeId) ?: return@forEach
        key("chat-window-$addresseeId") {
            ChatWindow(app, addressee)
        }
    }

    // Transient inbound popups, stacked from the bottom-right of the screen.
    app.notifications.toList().forEachIndexed { index, notification ->
        key("notification-${notification.id}") {
            NotificationWindow(app, notification, index)
        }
    }
}

// --------------------------------------------------------------- main window

@Composable
private fun MainWindowContent(app: AhccAppState) {
    val connection by app.client.connectionState.collectAsState()
    val lastError by app.client.lastError.collectAsState()
    val telegramAuth by app.client.telegram.auth.collectAsState()

    if (app.showAddReceiver) {
        AddReceiverDialog(
            onDismiss = { app.showAddReceiver = false },
            onConfirm = { name, key -> app.addAddressee(name, key) },
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
                        when (app.config.channel) {
                            ChatChannel.Telegram ->
                                "${connectionLabel(connection)} · Telegram"
                            ChatChannel.HttpSse ->
                                "${connectionLabel(connection)} · HTTP · ${app.config.model}"
                        },
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Agents ${app.addressees.size} · open ${app.openAddresseeIds.size}",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                lastError?.let {
                    Text(it, color = TgTheme.Danger, style = MaterialTheme.typography.labelSmall)
                }
                if (app.config.channel == ChatChannel.Telegram &&
                    telegramAuth !is DesktopTelegramAuth.Ready
                ) {
                    Text(
                        telegramAuthLabel(telegramAuth),
                        color = TgTheme.Warn,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            TextButton(onClick = { app.clearLog() }) {
                Text("Clear log", color = TgTheme.Text)
            }
            TextButton(onClick = { app.showSettings = !app.showSettings }) {
                Text(
                    if (app.showSettings) "Logs" else "Settings",
                    color = TgTheme.Text,
                )
            }
            IconButton(onClick = { app.toggleConnection() }) {
                Icon(
                    if (connection == ConnectionState.Connected) Icons.Default.LinkOff else Icons.Default.Link,
                    contentDescription = "Toggle connection",
                    tint = TgTheme.Accent,
                )
            }
        }

        LayoutRow(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AgentSidebar(
                app = app,
                modifier = Modifier.width(240.dp).fillMaxHeight(),
            )
            if (app.showSettings) {
                SettingsPanel(
                    config = app.config,
                    startOnLaunch = app.startOnLaunch,
                    telegramAuth = telegramAuth,
                    addressees = app.addressees,
                    currentAddressee = app.settingsAddressee,
                    defaultAddressee = app.defaultAddressee,
                    onChange = { app.updateConfig(it) },
                    onStartOnLaunchChange = {
                        app.startOnLaunch = it
                        DesktopUiSettings.startOnLaunch = it
                    },
                    onSelectAddressee = { app.settingsAddresseeId = it.id },
                    onPatchAddressee = { app.patchAddressee(it) },
                    onAddAddressee = { app.showAddReceiver = true },
                    onDeleteAddressee = { app.deleteAddressee(app.settingsAddresseeId) },
                    onSetDefaultAddressee = { app.setDefaultAddressee(app.settingsAddresseeId) },
                    onTelegramPhone = { app.client.telegram.submitPhone(it) },
                    onTelegramCode = { app.client.telegram.submitCode(it) },
                    onTelegramPassword = { app.client.telegram.submitPassword(it) },
                    onTelegramConnect = {
                        app.persistConfig(app.config)
                        val bot = app.effectiveBot(app.defaultAddressee)
                            .ifBlank { app.config.telegramBotUsername }
                        app.client.connect(app.config.copy(telegramBotUsername = bot))
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                )
            } else {
                LogPane(
                    app = app,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * Per-agent background tint, chosen per agent and applied to the agent's row in
 * the main-window sidebar and to the title bar of its chat window.
 * A row is drawn at full strength while the agent's window is open and faded
 * (see [agentRowBackground]) while it is closed.
 */
private fun agentTint(addresseeId: String): Color? = when (addresseeId) {
    Addressee.MAIN_AGENT_ID -> Color(0xFF123A5E)      // MainAgent — blue
    "assistant" -> Color(0xFF14532D)                  // Assistant — green
    "english_tutor" -> Color(0xFF115E59)              // EnglishTutor — teal
    "task_scheduler" -> Color(0xFF1E2A6B)             // TaskScheduler — indigo
    "biostack" -> Color(0xFF365314)                   // BioStack — olive
    "accountant" -> Color(0xFF713F12)                 // Accountant — amber-brown
    "sysadmin" -> Color(0xFF4C1D95)                   // SysAdmin — violet
    "trader" -> Color(0xFF065F46)                     // Trader — emerald
    "futures_trader" -> Color(0xFF7C2D12)             // FuturesTrader — rust
    Addressee.OPERATOR_ID -> Color(0xFF0B3B47)        // Operator — cyan
    else -> null
}

/** Sidebar background for one agent row: full tint when open, 20% transparent when closed. */
private fun agentRowBackground(addresseeId: String, isOpen: Boolean): Color {
    val tint = agentTint(addresseeId)
    return when {
        tint == null -> if (isOpen) TgTheme.InputField else Color.Transparent
        isOpen -> tint
        else -> tint.copy(alpha = 0.8f)
    }
}

@Composable
private fun AgentSidebar(app: AhccAppState, modifier: Modifier = Modifier) {
    LayoutColumn(
        modifier = modifier
            .background(TgTheme.Panel)
            .verticalScroll(rememberScrollState())
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Agents",
            style = MaterialTheme.typography.titleSmall,
            color = TgTheme.Text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
        app.addressees.forEach { addressee ->
            key("sidebar-${addressee.id}") {
                AgentSidebarRow(app, addressee)
            }
        }
    }
}

@Composable
private fun AgentSidebarRow(app: AhccAppState, addressee: Addressee) {
    val unreadCount = app.unread[addressee.id] ?: 0
    val isOpen = app.openAddresseeIds.contains(addressee.id)
    LayoutRow(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(agentRowBackground(addressee.id, isOpen))
            .clickable { app.openWindow(addressee.id) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        LayoutColumn(modifier = Modifier.weight(1f)) {
            Text(
                addressee.displayName,
                color = TgTheme.Text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unreadCount > 0) {
            LayoutBox(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(TgTheme.Accent)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unreadCount > 99) "99+" else unreadCount.toString(),
                    color = TgTheme.ChatBg,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun LogPane(app: AhccAppState, modifier: Modifier = Modifier) {
    val lines = app.logLines
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(lines.lastIndex)
    }
    LayoutColumn(modifier = modifier.background(TgTheme.ChatBg)) {
        LayoutRow(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(TgTheme.Panel)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "Live log",
                style = MaterialTheme.typography.titleSmall,
                color = TgTheme.Text,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${lines.size} events",
                style = MaterialTheme.typography.labelSmall,
                color = TgTheme.Secondary,
            )
        }
        if (lines.isEmpty()) {
            LayoutBox(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Inbound and outbound chat events appear here (newest at the bottom).\n" +
                        "Each agent has its own chat window — click a name on the left to open it.",
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
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lines.size) { index ->
                    val entry = lines[index]
                    val firstLine = entry.lineSequence().firstOrNull().orEmpty()
                    val direction = firstLine.split(' ').getOrNull(1).orEmpty().ifBlank { "SYS" }
                    val tint = when (direction) {
                        "OUT" -> TgTheme.Accent
                        "IN" -> TgTheme.Ok
                        else -> TgTheme.Warn
                    }
                    LayoutColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TgTheme.Panel)
                            .padding(8.dp),
                    ) {
                        Text(
                            direction,
                            color = tint,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            entry,
                            color = TgTheme.Text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- chat windows

@Composable
private fun ApplicationScope.ChatWindow(app: AhccAppState, addressee: Addressee) {
    val windowState = remember(addressee.id) { app.windowStateFor(addressee.id) }
    Window(
        onCloseRequest = { app.closeWindow(addressee.id) },
        title = "AHCC · ${addressee.displayName}",
        state = windowState,
        undecorated = true,
    ) {
        DisposableEffect(addressee.id) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent?) {
                    app.setWindowFocused(addressee.id, true)
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    app.setWindowFocused(addressee.id, false)
                }
            }
            window.addWindowFocusListener(listener)
            onDispose {
                window.removeWindowFocusListener(listener)
                if (app.focusedAddresseeId == addressee.id) {
                    app.setWindowFocused(addressee.id, false)
                }
            }
        }
        val focusTick = app.windowFocusTick[addressee.id]
        LaunchedEffect(focusTick) {
            if (focusTick == null) return@LaunchedEffect
            windowState.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        MaterialTheme(colorScheme = TgTheme.colorScheme()) {
            LayoutColumn(modifier = Modifier.fillMaxSize().background(TgTheme.ChatBg)) {
                AhccTitleBar(
                    title = "${addressee.displayName} · @" +
                        app.effectiveBot(addressee).ifBlank { "bot" },
                    windowState = windowState,
                    onClose = { app.closeWindow(addressee.id) },
                    background = agentTint(addressee.id) ?: TgTheme.TitleBar,
                )
                ChatPane(app, addressee)
            }
        }
    }
}

@Composable
private fun ChatPane(app: AhccAppState, addressee: Addressee) {
    val connection by app.client.connectionState.collectAsState()
    val messages = app.histories[addressee.id].orEmpty()
    val draft = app.drafts[addressee.id] ?: TextFieldValue("")
    val pendingFile = app.pendingAttach[addressee.id]
    val listState = rememberLazyListState()

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.inlineKeyboard?.rows?.size,
        messages.lastOrNull()?.isStreaming,
        messages.lastOrNull()?.isComplete,
    ) {
        if (messages.isEmpty()) return@LaunchedEffect
        val last = messages.lastIndex
        // animateScrollToItem alone stops at the *start* of a tall bubble — jump to end.
        listState.scrollToItem(last, scrollOffset = Int.MAX_VALUE / 4)
        listState.animateScrollToItem(last, scrollOffset = Int.MAX_VALUE / 4)
    }

    LayoutColumn(modifier = Modifier.fillMaxSize().background(TgTheme.ChatBg)) {
        LayoutRow(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(TgTheme.Panel)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "${addressee.displayName} · ${connectionLabel(connection)}",
                color = TgTheme.Secondary,
                style = MaterialTheme.typography.labelSmall,
            )
            when {
                app.waitingReply && app.awaitingAddresseeId == addressee.id -> Text(
                    "Hermes is typing…",
                    color = TgTheme.Warn,
                    style = MaterialTheme.typography.labelSmall,
                )
                app.waitingReply -> Text(
                    "Waiting on other agent…",
                    color = TgTheme.Warn,
                    style = MaterialTheme.typography.labelSmall,
                )
                app.lastReplyComplete && app.lastReplyAddresseeId == addressee.id -> Text(
                    "Reply received ✓",
                    color = TgTheme.Ok,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            LayoutSpacer(modifier = Modifier.weight(1f))
            if (app.lastFailedOutbound?.first == addressee.id && !app.waitingReply) {
                TextButton(
                    onClick = { app.resendLastFailed(addressee) },
                    enabled = connection == ConnectionState.Connected,
                ) {
                    Text("Resend", color = TgTheme.Warn)
                }
            }
            TextButton(onClick = { app.clearChat(addressee) }) {
                Text("Clear", color = TgTheme.Text)
            }
            TextButton(
                onClick = { app.startNewSession(addressee) },
                enabled = !app.waitingReply,
            ) {
                Text("New session", color = TgTheme.Text)
            }
        }

        if (messages.isEmpty()) {
            LayoutBox(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (app.config.channel) {
                        ChatChannel.Telegram ->
                            "${addressee.displayName} keeps its own chat window.\n" +
                                "Messages arriving while this window is not focused raise a popup.\n" +
                                "Enter — send · Shift+Enter — newline · Paperclip — attach."
                        ChatChannel.HttpSse ->
                            "HTTP SSE (Inference) — no server-side tools/routing.\n" +
                                "Enter — send · Shift+Enter — newline · Paperclip — attach."
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
                        onInlineButton = { action -> app.onInlineButton(addressee.id, msg.id, action) },
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
            pendingFile?.let { file ->
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
                    IconButton(onClick = { app.pendingAttach.remove(addressee.id) }) {
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
                    onClick = { app.pickAttachment(addressee) },
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
                    onValueChange = { app.drafts[addressee.id] = it },
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (event.key != Key.Enter && event.key != Key.NumPadEnter) {
                                return@onPreviewKeyEvent false
                            }
                            // Desktop TextField often drops Shift+Enter; insert at caret.
                            if (event.isShiftPressed) {
                                val text = draft.text
                                val start = draft.selection.min
                                val end = draft.selection.max
                                val inserted = text.replaceRange(start, end, "\n")
                                val caret = start + 1
                                app.drafts[addressee.id] = TextFieldValue(
                                    text = inserted,
                                    selection = TextRange(caret),
                                )
                                true
                            } else {
                                if (!app.waitingReply) app.send(addressee)
                                true
                            }
                        },
                    enabled = connection == ConnectionState.Connected,
                    singleLine = false,
                    placeholder = {
                        Text(
                            when {
                                pendingFile != null -> "Caption for file (Enter / Shift+Enter)…"
                                app.waitingReply -> "Waiting on other agent…"
                                else -> "Message ${addressee.displayName}… (Shift+Enter = newline)"
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
                    onClick = { app.send(addressee) },
                    enabled = connection == ConnectionState.Connected &&
                        !app.waitingReply &&
                        (draft.text.isNotBlank() || pendingFile != null),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

// -------------------------------------------------------------- notifications

@Composable
private fun ApplicationScope.NotificationWindow(
    app: AhccAppState,
    notification: AhccNotification,
    index: Int,
) {
    val screen = Toolkit.getDefaultToolkit().screenSize
    val widthPx = 360
    val heightPx = 96
    val margin = 18
    val x = (screen.width - widthPx - margin).coerceAtLeast(0)
    val y = (screen.height - heightPx - margin - index * (heightPx + 10)).coerceAtLeast(0)
    val state = remember(notification.id) {
        WindowState(
            position = WindowPosition(x.dp, y.dp),
            size = DpSize(widthPx.dp, heightPx.dp),
        )
    }
    Window(
        onCloseRequest = { app.dismissNotification(notification.id) },
        title = "${notification.title} — new message",
        state = state,
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        focusable = false,
    ) {
        MaterialTheme(colorScheme = TgTheme.colorScheme()) {
            LayoutColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TgTheme.Panel)
                    .clickable {
                        app.dismissNotification(notification.id)
                        app.openWindow(notification.addresseeId)
                    }
                    .padding(12.dp),
            ) {
                Text(
                    notification.title,
                    color = TgTheme.Accent,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LayoutSpacer(modifier = Modifier.height(4.dp))
                Text(
                    notification.body.ifBlank { "(new message)" },
                    color = TgTheme.Text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LayoutSpacer(modifier = Modifier.height(4.dp))
                Text(
                    "Click to open chat",
                    color = TgTheme.Secondary,
                    style = MaterialTheme.typography.labelSmall,
                )
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
    if (compact) {
        // Match Clear / New session / Settings: TextButton chrome, no outlined field.
        LayoutBox(modifier = modifier) {
            TextButton(onClick = { expanded = true }) {
                Text("To ", color = TgTheme.Secondary)
                Text(
                    selected.displayName,
                    color = TgTheme.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Select addressee",
                    tint = TgTheme.Text,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(TgTheme.Panel),
            ) {
                addressees.forEach { addressee ->
                    DropdownMenuItem(
                        text = {
                            Text(addressee.displayName, color = TgTheme.Text)
                        },
                        onClick = {
                            onSelect(addressee)
                            expanded = false
                        },
                    )
                }
            }
        }
        return
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        val fieldLabel = if (selected.id == defaultAddressee.id) {
            "${selected.displayName} (default)"
        } else {
            selected.displayName
        }
        OutlinedTextField(
            value = fieldLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("To") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
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
                        Text(addressee.displayName, color = TgTheme.Text)
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
    onPatchAddressee: ((Addressee) -> Addressee) -> Unit,
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
                    var inboundDir by remember {
                        mutableStateOf(DesktopUiSettings.inboundFilesDir)
                    }
                    Text("Inbound files folder", style = MaterialTheme.typography.titleSmall, color = TgTheme.Text)
                    Text(
                        "Telegram files from agents are saved here automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TgTheme.Secondary,
                    )
                    LayoutRow(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactField(
                            value = inboundDir,
                            onValueChange = {
                                inboundDir = it
                                DesktopUiSettings.inboundFilesDir = it
                            },
                            label = "Save received files to",
                            width = 360.dp,
                        )
                        TextButton(
                            onClick = {
                                val chooser = javax.swing.JFileChooser().apply {
                                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Choose inbound files folder"
                                    currentDirectory = java.io.File(inboundDir).takeIf { it.isDirectory }
                                        ?: java.io.File(DesktopUiSettings.defaultInboundFilesDir())
                                }
                                val result = chooser.showOpenDialog(null)
                                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                                    val picked = chooser.selectedFile?.absolutePath ?: return@TextButton
                                    inboundDir = picked
                                    DesktopUiSettings.inboundFilesDir = picked
                                    runCatching { java.io.File(picked).mkdirs() }
                                }
                            },
                        ) {
                            Text("Browse…", color = TgTheme.Text)
                        }
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
                        label = "Fallback Telegram bot (if addressee has none)",
                        width = 320.dp,
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
                        onValueChange = { onChange(config.copy(telegramChatId = it.filter { ch -> ch.isDigit() || ch == '-' })) },
                        label = "Telegram user id (global, all bots)",
                        width = 280.dp,
                    )
                    Text(
                        "Portal CHANNELS allowlist / your numeric Telegram id (e.g. 6598396079). Same for every bot.",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
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
                        "Per-addressee bot → plain text (no TO:). Empty bot → fallback + TO: for specialists.",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    AddresseeDropdown(
                        addressees = addressees,
                        selected = currentAddressee,
                        defaultAddressee = defaultAddressee,
                        onSelect = onSelectAddressee,
                    )
                    CompactField(
                        value = currentAddressee.telegramBotUsername,
                        onValueChange = { bot ->
                            onPatchAddressee {
                                it.copy(telegramBotUsername = bot.trim().removePrefix("@"))
                            }
                        },
                        label = "Bot username",
                        width = 360.dp,
                    )
                    CompactField(
                        value = currentAddressee.telegramBotToken,
                        onValueChange = { token ->
                            onPatchAddressee { it.copy(telegramBotToken = token.trim()) }
                        },
                        label = "Bot token (BotFather)",
                        width = 420.dp,
                        secret = true,
                    )
                    Text(
                        "Username = chat target in Desktop. Token = for Portal CHANNELS (stored locally). " +
                            "User id is global under Credentials.",
                        color = TgTheme.Secondary,
                        style = MaterialTheme.typography.labelSmall,
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
            .padding(vertical = 4.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
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
                } else if (message.localAttachmentPath.isNullOrBlank()) {
                    Text("…", color = TgTheme.Secondary)
                }
                val attachPath = message.localAttachmentPath
                if (!attachPath.isNullOrBlank()) {
                    val label = message.attachmentName ?: java.io.File(attachPath).name
                    LayoutSpacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "📎 $label — Open",
                        color = TgTheme.Accent,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                runCatching {
                                    val f = java.io.File(attachPath)
                                    if (f.isFile) {
                                        java.awt.Desktop.getDesktop().open(f)
                                    } else {
                                        java.awt.Desktop.getDesktop().open(f.parentFile ?: f)
                                    }
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
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
