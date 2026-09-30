package com.nous.ahcc.presentation.chat

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.BuildConfig
import com.nous.ahcc.domain.model.Addressee
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MediaKind
import com.nous.ahcc.domain.model.MessageRole
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: HermesChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenBluetoothTest: () -> Unit,
    onOpenToolLog: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        viewModel.claimHeadsetButtons()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.claimHeadsetButtons()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) viewModel.onCameraPhotoCaptured(file)
        else file?.delete()
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()
        val mime = context.contentResolver.getType(uri)
        viewModel.onFilePicked(uri, name, mime)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* retry happens via user action */ }

    fun ensurePerms(vararg perms: String, then: () -> Unit) {
        val need = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) then() else permissionLauncher.launch(need.toTypedArray())
    }

    fun launchCamera() {
        ensurePerms(Manifest.permission.CAMERA) {
            val file = File(context.cacheDir, "photo_capture_${System.currentTimeMillis()}.jpg")
            pendingPhoto = file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            takePicture.launch(uri)
        }
    }

    fun launchMic() {
        ensurePerms(Manifest.permission.RECORD_AUDIO) {
            viewModel.toggleVoiceRecording()
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        val last = state.messages.lastIndex
        if (last >= 0) listState.animateScrollToItem(last)
    }

    LaunchedEffect(state.lastError) {
        val err = state.lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(err)
        viewModel.clearError()
    }

    LaunchedEffect(state.statusNotice) {
        val notice = state.statusNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.clearStatusNotice()
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
                hostLabel = "Telegram · ${state.config.telegramChatId.ifBlank { "нет chat id" }}",
                recording = state.isRecording,
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
                onClear = viewModel::clearChat,
                onNewSession = viewModel::newSession,
                onShutdown = {
                    val activity = context as? Activity
                    viewModel.shutdownApp { activity?.finishAndRemoveTask() }
                }
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
            if (state.isSending && !state.isRecording) {
                TypingBanner()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.messages.isEmpty()) {
                    EmptyHero(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(
                                message = message,
                                playingPath = state.playingPath,
                                onPlayVoice = viewModel::playInbound
                            )
                        }
                    }
                }
                if (state.isRecording) {
                    ListeningOrb(Modifier.fillMaxSize())
                }
            }

            val composerBusy = state.isSending || state.isRecording
            ComposerBar(
                draft = state.draft,
                enabled = state.connectionState == ConnectionState.Connected,
                composerBusy = composerBusy,
                addressees = Addressee.catalog,
                selectedAddressee = state.currentAddressee,
                onAddresseeSelect = viewModel::onAddresseeSelected,
                isRecording = state.isRecording,
                onDraftChange = viewModel::onDraftChange,
                onSend = { viewModel.sendPrompt() },
                onAttach = {
                    openDoc.launch(arrayOf("*/*"))
                },
                onCamera = { launchCamera() },
                onMic = { launchMic() }
            )
            Text(
                text = state.toolStatus.ifBlank { "Служебные команды" },
                color = Color(0xFFE8F4F3),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF123D45))
                    .clickable(onClick = onOpenToolLog)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ListeningOrb(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "listen")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4800, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        modifier = modifier.background(Color(0xFF0B1F2A)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(240.dp)
                .graphicsLayer {
                    rotationZ = spin
                    scaleX = pulse
                    scaleY = pulse
                }
        ) {
            val c = center
            val r = size.minDimension
            drawCircle(
                color = Color(0xFF4C6FFF),
                radius = r * 0.34f,
                center = c + Offset(-r * 0.08f, -r * 0.04f),
                alpha = 0.9f,
            )
            drawCircle(
                color = Color(0xFF2EE6D6),
                radius = r * 0.28f,
                center = c + Offset(r * 0.1f, r * 0.02f),
                alpha = 0.8f,
            )
            drawCircle(
                color = Color(0xFFB388FF),
                radius = r * 0.22f,
                center = c + Offset(r * 0.01f, r * 0.12f),
                alpha = 0.75f,
            )
            drawCircle(
                color = Color(0xFFE8F4F3),
                radius = r * 0.07f,
                center = c,
                alpha = 0.35f,
            )
        }
        Text(
            text = "Слушаю",
            color = Color(0xFFE8F4F3),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun ChatTopBar(
    connectionState: ConnectionState,
    hostLabel: String,
    recording: Boolean,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBluetoothTest: () -> Unit,
    onClear: () -> Unit,
    onNewSession: () -> Unit,
    onShutdown: () -> Unit,
) {
    val statusColor = when {
        recording -> MaterialTheme.colorScheme.error
        connectionState == ConnectionState.Connected -> MaterialTheme.colorScheme.primary
        connectionState == ConnectionState.Connecting -> MaterialTheme.colorScheme.secondary
        connectionState == ConnectionState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = "${connectionState.name} В· $hostLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onShutdown) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = "Выключить",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        TextButton(onClick = onClear) {
            Text("Clear", color = MaterialTheme.colorScheme.onBackground)
        }
        TextButton(onClick = onNewSession) {
            Text("New", color = MaterialTheme.colorScheme.onBackground)
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
            text = "Text, photo, files and voice (BT Play / Mic) via AHCC_MEDIA_V1 to Hermes Cloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypingBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Hermes печатает",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge
        )
        TypingDots(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    playingPath: String?,
    onPlayVoice: (String) -> Unit
) {
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
        if (message.content.isBlank() && !message.isStreaming) return@Box
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
                    MessageRole.Assistant -> if (message.isStreaming) "Hermes · печатает" else "Hermes"
                    MessageRole.Tool -> "Tool В· ${message.toolName ?: "?"}"
                    MessageRole.System -> "System"
                },
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            if (message.isStreaming && message.content.isBlank()) {
                TypingDots(color = fg)
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = fg
                )
            }
            val voicePath = message.localMediaPath
                ?: message.inboundMedia.firstOrNull { it.kind == MediaKind.Voice }?.filePath
            if (voicePath != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onPlayVoice(voicePath) }) {
                    Icon(
                        imageVector = if (playingPath == voicePath) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (playingPath == voicePath) "Stop" else "Voice")
                }
            } else if (message.attachmentKind == MediaKind.Photo) {
                Spacer(Modifier.height(4.dp))
                Text("Photo: ${message.attachmentName ?: "photo"}", color = fg.copy(alpha = 0.85f))
            } else if (message.attachmentKind == MediaKind.File) {
                Spacer(Modifier.height(4.dp))
                Text("File: ${message.attachmentName ?: "file"}", color = fg.copy(alpha = 0.85f))
            }
            message.inboundMedia.filter { it.kind == MediaKind.Voice && it.filePath != voicePath }
                .forEach { media ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { onPlayVoice(media.filePath) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(media.name)
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
    enabled: Boolean,
    onSelect: (Addressee) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Адресат (Gate = без TO:)") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.exposedDropdownSize(matchTextFieldWidth = true),
        ) {
            addressees.forEach { addressee ->
                DropdownMenuItem(
                    text = { Text(addressee.displayName) },
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
private fun ComposerBar(
    draft: String,
    enabled: Boolean,
    composerBusy: Boolean,
    addressees: List<Addressee>,
    selectedAddressee: Addressee,
    onAddresseeSelect: (Addressee) -> Unit,
    isRecording: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onMic: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AddresseeDropdown(
            addressees = addressees,
            selected = selectedAddressee,
            enabled = enabled && !composerBusy,
            onSelect = onAddresseeSelect,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onAttach, enabled = enabled) {
                Icon(Icons.Default.AttachFile, contentDescription = "File")
            }
            IconButton(onClick = onCamera, enabled = enabled) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Camera")
            }
            IconButton(onClick = onMic, enabled = enabled) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Voice",
                    tint = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onBackground
                )
            }
        }
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
                        if (enabled) "Message... Enter to send"
                    else "Connect first",
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
}
