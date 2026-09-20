package com.nous.ahcc.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import com.nous.ahcc.BuildConfig
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MessageRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: HermesChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenBluetoothTest: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.lastError) {
        val err = state.lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        viewModel.clearError()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ChatTopBar(
                connectionState = state.connectionState,
                hostLabel = when (state.config.transport) {
                    com.nous.ahcc.domain.model.TransportMode.HttpSse ->
                        "HTTP · ${state.config.model}"
                    com.nous.ahcc.domain.model.TransportMode.WebSocket ->
                        "WS · ${state.config.host}:${state.config.port}"
                },
                onToggleConnection = {
                    if (state.connectionState == ConnectionState.Connected ||
                        state.connectionState == ConnectionState.Connecting
                    ) {
                        viewModel.disconnect()
                    } else {
                        viewModel.connect()
                    }
                },
                onOpenSettings = onOpenSettings,
                onOpenBluetoothTest = onOpenBluetoothTest,
                onClear = viewModel::clearChat
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
        ) {
            if (state.messages.isEmpty()) {
                EmptyHero(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
            }

            ComposerBar(
                draft = state.draft,
                enabled = state.connectionState == ConnectionState.Connected,
                onDraftChange = viewModel::onDraftChange,
                onSend = { viewModel.sendPrompt() }
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    connectionState: ConnectionState,
    hostLabel: String,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothTest: () -> Unit,
    onClear: () -> Unit
) {
    val statusColor = when (connectionState) {
        ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        ConnectionState.Connecting -> MaterialTheme.colorScheme.secondary
        ConnectionState.Error -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "AHCC v${BuildConfig.VERSION_LABEL}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "${connectionState.name} · $hostLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        TextButton(onClick = onClear) {
            Text("Clear")
        }
        IconButton(onClick = onOpenBluetoothTest) {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = "BT test",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onToggleConnection) {
            Icon(
                imageVector = if (connectionState == ConnectionState.Connected) {
                    Icons.Default.LinkOff
                } else {
                    Icons.Default.Link
                },
                contentDescription = "Toggle connection",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun EmptyHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Android Hermes\nCloud Chat",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Chat via Nous Inference (HTTP SSE). WebSocket bridge remains available in Settings for local Hermes.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.User
    val align = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = when (message.role) {
        MessageRole.User -> MaterialTheme.colorScheme.primary
        MessageRole.Assistant -> MaterialTheme.colorScheme.surfaceVariant
        MessageRole.Tool -> MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        MessageRole.System -> MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
    }
    val fg = when (message.role) {
        MessageRole.User -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = when (message.role) {
                    MessageRole.User -> "You"
                    MessageRole.Assistant -> if (message.isStreaming) "Hermes · streaming" else "Hermes"
                    MessageRole.Tool -> "Tool · ${message.toolName ?: "?"}"
                    MessageRole.System -> "System"
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.content.ifBlank { "…" },
                style = MaterialTheme.typography.bodyLarge,
                color = fg
            )
        }
    }
}

@Composable
private fun ComposerBar(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    // Enter = send, Shift+Enter = newline (hardware / desktop keyboards)
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        if (event.isShiftPressed) {
                            false
                        } else {
                            if (enabled && draft.isNotBlank()) onSend()
                            true
                        }
                    } else {
                        false
                    }
                },
            enabled = enabled,
            placeholder = {
                Text(
                    if (enabled) "Message Hermes… (Enter send, Shift+Enter newline)"
                    else "Connect to Hermes first",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (enabled && draft.isNotBlank()) onSend()
                }
            ),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
            )
        )
        AnimatedVisibility(
            visible = draft.isNotBlank(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut()
        ) {
            FilledIconButton(
                onClick = onSend,
                enabled = enabled,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
