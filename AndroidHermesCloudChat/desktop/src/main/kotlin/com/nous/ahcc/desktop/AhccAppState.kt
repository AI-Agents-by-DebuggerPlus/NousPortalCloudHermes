package com.nous.ahcc.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import com.nous.ahcc.desktop.config.DesktopChatHistoryStore
import com.nous.ahcc.desktop.config.DesktopUiSettings
import com.nous.ahcc.desktop.log.DesktopChatLog
import com.nous.ahcc.desktop.media.DesktopCuePlayer
import com.nous.ahcc.desktop.media.DesktopMediaEnvelope
import com.nous.ahcc.desktop.model.Addressee
import com.nous.ahcc.desktop.model.ChatAttachmentRef
import com.nous.ahcc.desktop.model.ChatChannel
import com.nous.ahcc.desktop.model.ChatMessage
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.HermesResponse
import com.nous.ahcc.desktop.model.MessageRole
import com.nous.ahcc.desktop.model.TelegramInlineAction
import com.nous.ahcc.desktop.model.TelegramInlineKeyboard
import com.nous.ahcc.desktop.net.AhccSupabaseClient
import com.nous.ahcc.desktop.net.DesktopChatGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/** Transient popup shown for an inbound message to a window that is not focused. */
data class AhccNotification(
    val id: String,
    val addresseeId: String,
    val title: String,
    val body: String,
)

/**
 * Shared, window-independent application state and chat business logic.
 *
 * One instance is created in [main] and shared by the main window (agent list +
 * logs), every per-addressee chat window and the notification popups. A chat
 * window is permanently bound to its addressee; nothing ever re-points an
 * existing window at another agent.
 */
class AhccAppState(val client: DesktopChatGateway) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var config by mutableStateOf(DesktopUiSettings.loadConnection())
        private set
    var startOnLaunch by mutableStateOf(DesktopUiSettings.startOnLaunch)
    var showSettings by mutableStateOf(false)
    var showAddReceiver by mutableStateOf(false)

    /** Full addressee catalog; list order = sidebar order. */
    val addressees = mutableStateListOf<Addressee>()
    var defaultAddresseeId by mutableStateOf(Addressee.MAIN_AGENT_ID)
        private set
    /** Addressee currently being edited in Settings › Addressees. */
    var settingsAddresseeId by mutableStateOf(Addressee.MAIN_AGENT_ID)

    /** Per-addressee chat threads. */
    val histories = mutableStateMapOf<String, List<ChatMessage>>()
    /** Per-addressee window state: draft text and pending attachment. */
    val drafts = mutableStateMapOf<String, TextFieldValue>()
    val pendingAttach = mutableStateMapOf<String, File>()

    /** Addressee ids whose chat window is currently open. */
    val openAddresseeIds = mutableStateListOf<String>()
    /** Unread counters rendered as sidebar badges. */
    val unread = mutableStateMapOf<String, Int>()
    /** Bumped to ask an existing window to raise itself to the front. */
    val windowFocusTick = mutableStateMapOf<String, Long>()
    /** Active inbound popup notifications. */
    val notifications = mutableStateListOf<AhccNotification>()
    /** Live copy of DesktopChatLog output (newest last, capped). */
    val logLines = mutableStateListOf<String>()

    /** Addressee the in-flight request belongs to (one request at a time). */
    var awaitingAddresseeId by mutableStateOf<String?>(null)
        private set
    var waitingReply by mutableStateOf(false)
        private set
    var lastReplyComplete by mutableStateOf(false)
        private set
    /** Addressee the last completed/failed reply belongs to. */
    var lastReplyAddresseeId by mutableStateOf<String?>(null)
        private set
    /** Last outbound text that failed: (addresseeId, userText) — for Resend. */
    var lastFailedOutbound by mutableStateOf<Pair<String, String>?>(null)
        private set
    /** Addressee whose chat window currently holds OS focus (null = none). */
    var focusedAddresseeId by mutableStateOf<String?>(null)

    /** Set when `/new now` must be sent as soon as the link comes up. */
    private var sendNewAfterConnectFor: String? = null

    private val windowStates = mutableMapOf<String, WindowState>()

    val defaultAddressee: Addressee
        get() = Addressee.resolve(addressees, defaultAddresseeId)

    val settingsAddressee: Addressee
        get() = Addressee.resolve(addressees, settingsAddresseeId)

    /** Catalog came from an existing properties file (not rebuilt from defaults). */
    private val catalogLoadedFromDisk = DesktopUiSettings.hasAddresseeCatalog

    init {
        val loaded = DesktopUiSettings.addresseeCatalog
        val catalog = Addressee.normalizeCatalog(
            if (loaded.isEmpty()) Addressee.defaultCatalog else loaded,
        )
        val fallback = DesktopUiSettings.loadConnection().telegramBotUsername
            .trim().removePrefix("@")
        val repaired = if (fallback.isBlank()) catalog else catalog.map { a ->
            if (a.id == Addressee.MAIN_AGENT_ID && a.telegramBotUsername.isBlank()) {
                a.copy(telegramBotUsername = fallback)
            } else a
        }
        addressees.addAll(repaired)
        defaultAddresseeId = Addressee.resolve(repaired, DesktopUiSettings.defaultAddresseeId).id
        settingsAddresseeId = Addressee.resolve(repaired, DesktopUiSettings.currentAddresseeId).id
    }

    // ---------------------------------------------------------------- startup

    /** Wires logging, inbound push, response stream and the initial load. */
    fun start() {
        DesktopChatLog.sink = { text -> scope.launch { appendLog(text) } }
        // Persist repaired catalog (e.g. Operator bot stolen by MainAgent username).
        // Never rewrite a catalog we did not actually read: a blank/partial read must not
        // overwrite the user's file with the built-in defaults (bots would be lost).
        if (catalogLoadedFromDisk) persistAddressees()
        ensureHistoryLoaded(defaultAddressee.id)
        recoverFailedOutbound(defaultAddressee.id)
        run {
            val opId = Addressee.OPERATOR_ID
            if (opId == defaultAddressee.id) return@run
            ensureHistoryLoaded(opId)
            recoverFailedOutbound(opId)
        }
        client.telegram.setInboundPush { botUsername, chunk, keyboard, attachment ->
            onInboundPush(botUsername, chunk, keyboard, attachment)
        }
        scope.launch { client.responses.collect { response -> onResponse(response) } }
        scope.launch {
            client.connectionState.collect { state ->
                if (state == ConnectionState.Connected) {
                    syncRegisteredBots()
                    sendNewAfterConnectFor?.let { id ->
                        sendNewAfterConnectFor = null
                        sendTextOnly(
                            Addressee.resolve(addressees, id),
                            "/new now",
                            passthrough = true,
                        )
                    }
                }
            }
        }
        if (startOnLaunch && client.connectionState.value == ConnectionState.Disconnected) {
            connectWithSession(forceNew = false)
        }
    }

    private fun recoverFailedOutbound(addresseeId: String) {
        val list = histories[addresseeId].orEmpty()
        val last = list.lastOrNull() ?: return
        val timedOut = last.role == MessageRole.System &&
            last.content.contains("No text reply", ignoreCase = true)
        if (!timedOut) return
        val userMsg = list.asReversed().firstOrNull { it.role == MessageRole.User } ?: return
        if (lastFailedOutbound == null) lastFailedOutbound = addresseeId to userMsg.content
    }

    // ------------------------------------------------------------------- logs

    private fun appendLog(text: String) {
        if (text.isBlank()) return
        logLines.add(text)
        val overflow = logLines.size - LOG_CAP
        if (overflow > 0) repeat(overflow) { if (logLines.isNotEmpty()) logLines.removeAt(0) }
    }

    fun clearLog() {
        logLines.clear()
    }

    // ---------------------------------------------------------------- windows

    /** Persistent per-addressee window state (position/size kept while open). */
    fun windowStateFor(addresseeId: String): WindowState = windowStates.getOrPut(addresseeId) {
        WindowState(size = DpSize(880.dp, 680.dp))
    }

    fun openWindow(addresseeId: String) {
        if (!openAddresseeIds.contains(addresseeId)) openAddresseeIds.add(addresseeId)
        unread.remove(addresseeId)
        ensureHistoryLoaded(addresseeId)
        windowFocusTick[addresseeId] = (windowFocusTick[addresseeId] ?: 0L) + 1L
        notifications.removeAll { it.addresseeId == addresseeId }
    }

    fun closeWindow(addresseeId: String) {
        openAddresseeIds.remove(addresseeId)
        windowStates.remove(addresseeId)
        windowFocusTick.remove(addresseeId)
        if (focusedAddresseeId == addresseeId) focusedAddresseeId = null
    }

    fun dismissNotification(id: String) {
        notifications.removeAll { it.id == id }
    }

    fun setWindowFocused(addresseeId: String, focused: Boolean) {
        if (focused) {
            focusedAddresseeId = addresseeId
        } else if (focusedAddresseeId == addresseeId) {
            focusedAddresseeId = null
        }
    }

    // -------------------------------------------------------------- addressees

    fun addresseeById(id: String): Addressee? = addressees.firstOrNull { it.id == id }

    fun effectiveBot(addressee: Addressee): String =
        Addressee.effectiveBotUsername(addressee, config.telegramBotUsername)

    fun findAddresseeByBot(botUsername: String): Addressee? {
        val key = botUsername.trim().removePrefix("@")
        if (key.isEmpty()) return null
        return addressees.firstOrNull { effectiveBot(it).equals(key, ignoreCase = true) }
    }

    fun syncRegisteredBots() {
        if (config.channel != ChatChannel.Telegram) return
        val bots = addressees.map { effectiveBot(it) }.filter { it.isNotBlank() }.distinct()
        client.registerTelegramBots(bots)
    }

    fun persistAddressees(
        catalog: List<Addressee> = addressees.toList(),
        defaultId: String = defaultAddresseeId,
        currentId: String = settingsAddresseeId,
    ) {
        DesktopUiSettings.persistAddressees(catalog, defaultId, currentId)
    }

    fun persistConfig(c: ConnectionConfig) {
        DesktopUiSettings.persistConnection(c, startOnLaunch)
    }

    fun updateConfig(c: ConnectionConfig) {
        config = c
        persistConfig(c)
    }

    fun patchAddressee(transform: (Addressee) -> Addressee) {
        val updated = transform(settingsAddressee)
        val next = addressees.map { if (it.id == updated.id) updated else it }
        addressees.clear()
        addressees.addAll(next)
        persistAddressees(catalog = next, currentId = updated.id)
        if (config.channel == ChatChannel.Telegram) {
            client.switchTelegramBot(effectiveBot(updated))
            syncRegisteredBots()
        }
    }

    fun addAddressee(displayName: String, addressingKey: String?) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        val id = addressingKey?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: Addressee.newId(name, addressees)
        if (addressees.any { it.id.equals(id, ignoreCase = true) }) return
        val next = Addressee(id, name)
        addressees.add(next)
        settingsAddresseeId = next.id
        persistAddressees(catalog = addressees.toList(), currentId = next.id)
    }

    fun deleteAddressee(addresseeId: String) {
        if (addresseeId == Addressee.MAIN_AGENT_ID) return
        addressees.removeAll { it.id == addresseeId }
        closeWindow(addresseeId)
        unread.remove(addresseeId)
        drafts.remove(addresseeId)
        pendingAttach.remove(addresseeId)
        if (defaultAddresseeId == addresseeId) {
            defaultAddresseeId = Addressee.resolve(addressees, Addressee.MAIN_AGENT_ID).id
        }
        settingsAddresseeId = defaultAddresseeId
        persistAddressees(
            catalog = addressees.toList(),
            defaultId = defaultAddresseeId,
            currentId = settingsAddresseeId,
        )
    }

    fun setDefaultAddressee(addresseeId: String) {
        defaultAddresseeId = Addressee.resolve(addressees, addresseeId).id
        persistAddressees()
    }

    // --------------------------------------------------------------- histories

    fun putMessages(addresseeId: String, list: List<ChatMessage>, persist: Boolean = true) {
        histories[addresseeId] = list
        if (persist) {
            scope.launch(Dispatchers.IO) {
                runCatching { DesktopChatHistoryStore.save(addresseeId, list) }
            }
        }
    }

    fun mutateMessages(addresseeId: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        putMessages(addresseeId, transform(histories[addresseeId].orEmpty()))
    }

    fun ensureHistoryLoaded(addresseeId: String) {
        if (histories.containsKey(addresseeId)) return
        val loaded = runCatching { DesktopChatHistoryStore.load(addresseeId) }
            .getOrDefault(emptyList())
        putMessages(addresseeId, loaded, persist = false)
    }

    fun clearChat(addressee: Addressee) {
        client.clearHistory()
        putMessages(addressee.id, emptyList())
        // Clear the window and both files that belong to this agent:
        // its stored history and its own log.
        DesktopChatLog.clearAgent(addressee.id)
        scope.launch(Dispatchers.IO) { DesktopChatHistoryStore.clear(addressee.id) }
    }

    // -------------------------------------------------------------- connection

    private fun supabase() = AhccSupabaseClient(config.supabaseUrl, config.supabaseAnonKey)

    fun connectWithSession(forceNew: Boolean, focus: Addressee = defaultAddressee) {
        scope.launch {
            if (forceNew) {
                putMessages(focus.id, emptyList())
                withContext(Dispatchers.IO) { DesktopChatHistoryStore.clear(focus.id) }
            } else {
                ensureHistoryLoaded(focus.id)
            }
            try {
                val resolved = withContext(Dispatchers.IO) {
                    supabase().resolveOrCreateSession(
                        forceNew = forceNew,
                        preferredId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                    )
                }
                val next = config.copy(sessionId = resolved)
                config = next
                persistConfig(next)
                val bot = effectiveBot(focus).ifBlank { next.telegramBotUsername }
                client.connect(next.copy(telegramBotUsername = bot))
                syncRegisteredBots()
            } catch (e: Exception) {
                putMessages(
                    focus.id,
                    listOf(
                        ChatMessage(
                            UUID.randomUUID().toString(),
                            MessageRole.System,
                            e.message ?: "Connect / Supabase failed",
                        ),
                    ),
                )
            }
        }
    }

    fun disconnect() {
        client.disconnect()
    }

    fun toggleConnection() {
        if (client.connectionState.value == ConnectionState.Connected ||
            client.connectionState.value == ConnectionState.Connecting
        ) {
            client.disconnect()
        } else {
            connectWithSession(forceNew = false)
        }
    }

    fun shutdown() {
        client.disconnect()
    }

    // ------------------------------------------------------------- inbound push

    private fun onInboundPush(
        botUsername: String,
        chunk: String,
        keyboard: TelegramInlineKeyboard?,
        attachment: ChatAttachmentRef?,
    ) {
        val addr = findAddresseeByBot(botUsername) ?: defaultAddressee
        ensureHistoryLoaded(addr.id)
        mutateMessages(addr.id) { prev ->
            val list = prev.toMutableList()
            val idx = list.indexOfLast { it.role == MessageRole.Assistant && it.isComplete }
            if (idx >= 0) {
                val old = list[idx]
                val nextKb = when {
                    keyboard != null -> keyboard
                    DesktopCuePlayer.hasSubstantialProse(chunk) -> null
                    else -> old.inlineKeyboard
                }
                list[idx] = old.copy(
                    content = (old.content + "\n\n" + chunk).trim(),
                    inlineKeyboard = nextKb,
                    localAttachmentPath = attachment?.localPath ?: old.localAttachmentPath,
                    attachmentName = attachment?.displayName ?: old.attachmentName,
                )
            } else {
                list += ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = chunk,
                    isStreaming = false,
                    isComplete = true,
                    inlineKeyboard = keyboard,
                    localAttachmentPath = attachment?.localPath,
                    attachmentName = attachment?.displayName,
                )
            }
            list
        }
        lastReplyComplete = true
        lastReplyAddresseeId = addr.id
        when {
            DesktopCuePlayer.looksLikeStillWorking(chunk) -> DesktopCuePlayer.playTool()
            DesktopCuePlayer.hasSubstantialProse(chunk) -> DesktopCuePlayer.playReply()
            chunk.isNotBlank() -> DesktopCuePlayer.playReply()
        }
        DesktopChatLog.inbound(
            sessionId = config.sessionId,
            addresseeId = addr.id,
            role = "assistant",
            text = chunk,
            note = "inbound-push",
        )
        val visible = openAddresseeIds.contains(addr.id) && focusedAddresseeId == addr.id
        if (!visible && chunk.isNotBlank()) notifyInbound(addr, chunk)
    }

    /**
     * The agent's window is not focused/open — raise a transient popup instead of
     * switching anything. Repeat chunks refresh the same popup (no badge spam).
     */
    private fun notifyInbound(addr: Addressee, chunk: String) {
        val firstLine = chunk.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val body = if (firstLine.length > 120) firstLine.take(117) + "…" else firstLine
        val existing = notifications.firstOrNull { it.addresseeId == addr.id }
        if (existing != null) {
            val idx = notifications.indexOf(existing)
            notifications[idx] = existing.copy(body = body)
            return
        }
        val id = UUID.randomUUID().toString()
        notifications.add(AhccNotification(id, addr.id, addr.displayName, body))
        unread[addr.id] = (unread[addr.id] ?: 0) + 1
        scope.launch {
            delay(NOTIFICATION_TTL_MS)
            notifications.removeAll { it.id == id }
        }
    }

    // ------------------------------------------------------------ response flow

    private suspend fun onResponse(response: HermesResponse) {
        val targetId = awaitingAddresseeId ?: defaultAddresseeId
        when (response.event) {
            "history" -> {
                if (histories[targetId].orEmpty().isNotEmpty()) return
                val items = response.history.orEmpty()
                var built = items.mapNotNull { item ->
                    val role = when (item.role.lowercase()) {
                        "user" -> MessageRole.User
                        "assistant" -> MessageRole.Assistant
                        "system" -> MessageRole.System
                        else -> return@mapNotNull null
                    }
                    ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = role,
                        content = item.content,
                    )
                }
                response.content?.takeIf { it.isNotBlank() && items.isEmpty() }?.let { note ->
                    built = built + ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = MessageRole.System,
                        content = note,
                    )
                }
                putMessages(targetId, built)
            }
            "token" -> {
                val delta = response.delta.orEmpty()
                if (delta.isEmpty()) return
                mutateMessages(targetId) { prev ->
                    prev.toMutableList().also { list ->
                        val idx = list.indexOfLast { it.role == MessageRole.Assistant && it.isStreaming }
                        if (idx >= 0) {
                            list[idx] = list[idx].copy(content = list[idx].content + delta)
                        } else {
                            list += ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, delta, true)
                        }
                    }
                }
            }
            "typing" -> {
                waitingReply = true
                lastReplyComplete = false
                mutateMessages(targetId) { prev ->
                    if (prev.any { it.role == MessageRole.Assistant && it.isStreaming }) prev
                    else prev + ChatMessage(
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
                if (DesktopCuePlayer.looksLikeStillWorking(text) ||
                    DesktopCuePlayer.looksLikeToolProgress(text)
                ) {
                    DesktopCuePlayer.playTool()
                }
                mutateMessages(targetId) { prev ->
                    prev.toMutableList().also { list ->
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
            }
            "done" -> {
                waitingReply = false
                lastReplyComplete = true
                lastReplyAddresseeId = targetId
                val streamed = histories[targetId].orEmpty()
                    .lastOrNull { it.role == MessageRole.Assistant }?.content.orEmpty()
                val finalText = response.content?.takeIf { it.isNotBlank() } ?: streamed
                val keyboard = response.inlineKeyboard
                val attachment = response.attachment
                mutateMessages(targetId) { prev ->
                    prev.map {
                        if (it.isStreaming) {
                            it.copy(
                                content = finalText.ifBlank { it.content },
                                isStreaming = false,
                                isComplete = true,
                                inlineKeyboard = keyboard,
                                localAttachmentPath = attachment?.localPath ?: it.localAttachmentPath,
                                attachmentName = attachment?.displayName ?: it.attachmentName,
                            )
                        } else it
                    }
                }
                awaitingAddresseeId = null
                if (finalText.isNotBlank()) {
                    when {
                        DesktopCuePlayer.looksLikeStillWorking(finalText) -> DesktopCuePlayer.playTool()
                        DesktopCuePlayer.hasSubstantialProse(finalText) -> DesktopCuePlayer.playReply()
                        else -> DesktopCuePlayer.playReply()
                    }
                    DesktopChatLog.inbound(
                        sessionId = config.sessionId,
                        addresseeId = targetId,
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
                awaitingAddresseeId = null
                lastReplyAddresseeId = targetId
                val err = response.error ?: "Error"
                DesktopChatLog.inbound(
                    sessionId = config.sessionId,
                    addresseeId = targetId,
                    role = "error",
                    text = err,
                )
                mutateMessages(targetId) { prev ->
                    prev.map { if (it.isStreaming) it.copy(isStreaming = false) else it } +
                        ChatMessage(UUID.randomUUID().toString(), MessageRole.System, err)
                }
            }
        }
    }

    // ----------------------------------------------------------------- sending

    fun sendTextOnly(addressee: Addressee, text: String, passthrough: Boolean = false) {
        val body = text.trim()
        if (body.isEmpty()) return
        val wire = if (passthrough) body else Addressee.wireText(body, addressee)
        DesktopChatLog.out(
            sessionId = config.sessionId,
            addresseeId = addressee.id,
            text = wire,
        )
        awaitingAddresseeId = addressee.id
        waitingReply = true
        lastReplyComplete = false
        mutateMessages(addressee.id) { prev ->
            prev +
                ChatMessage(UUID.randomUUID().toString(), MessageRole.User, body) +
                ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", isStreaming = true)
        }
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
                if (config.channel == ChatChannel.Telegram) {
                    client.switchTelegramBot(effectiveBot(addressee))
                }
                client.sendMessage(wire)
                lastFailedOutbound = null
            } catch (e: Exception) {
                waitingReply = false
                awaitingAddresseeId = null
                lastFailedOutbound = addressee.id to body
                val err = e.message ?: "send failed"
                DesktopChatLog.system(config.sessionId, err)
                mutateMessages(addressee.id) { prev ->
                    prev.map {
                        if (it.isStreaming) {
                            it.copy(
                                content = err,
                                isStreaming = false,
                                role = MessageRole.System,
                            )
                        } else it
                    }
                }
            }
        }
    }

    fun sendPendingFile(addressee: Addressee, file: File, caption: String?) {
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
                    val notify = Addressee.wireText(notifyBody, addressee)
                    DesktopChatLog.out(
                        sessionId = config.sessionId,
                        addresseeId = addressee.id,
                        text = notify,
                        note = "file=$label",
                    )
                    awaitingAddresseeId = addressee.id
                    mutateMessages(addressee.id) {
                        it +
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.User,
                                content = buildString {
                                    if (!caption.isNullOrBlank()) append(caption).append('\n')
                                    append("[file] $label")
                                },
                                localAttachmentPath = file.absolutePath,
                                attachmentName = fileName,
                            ) +
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.Assistant,
                                content = "",
                                isStreaming = true,
                            )
                    }
                    if (config.channel == ChatChannel.Telegram) {
                        client.switchTelegramBot(effectiveBot(addressee))
                    }
                    client.sendMedia(
                        envelopeText = notify,
                        historyPlaceholder = notify,
                        localPath = file.absolutePath,
                        asPhoto = false,
                    )
                    return@launch
                }

                val attachment = withContext(Dispatchers.IO) {
                    DesktopMediaEnvelope.fromFile(file)
                }
                val envelope = DesktopMediaEnvelope.buildText(caption, attachment, config.sessionId)
                val placeholder = DesktopMediaEnvelope.placeholder(attachment, caption)
                awaitingAddresseeId = addressee.id
                mutateMessages(addressee.id) {
                    it +
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.User,
                            content = placeholder,
                            localAttachmentPath = file.absolutePath,
                            attachmentName = file.name,
                        ) +
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.Assistant,
                            content = "",
                            isStreaming = true,
                        )
                }
                withContext(Dispatchers.IO) {
                    supabase().insert(
                        sessionId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                        content = placeholder,
                        recipientName = AhccSupabaseClient.SENDER_HERMES,
                    )
                }
                val mediaHint = Addressee.wireText(placeholder, addressee)
                DesktopChatLog.out(
                    sessionId = config.sessionId,
                    addresseeId = addressee.id,
                    text = mediaHint,
                    note = "media",
                )
                if (config.channel == ChatChannel.Telegram) {
                    client.switchTelegramBot(effectiveBot(addressee))
                }
                client.sendMedia(
                    envelopeText = envelope,
                    historyPlaceholder = mediaHint,
                    localPath = file.absolutePath,
                    asPhoto = attachment.kind == "photo",
                )
            } catch (e: Exception) {
                val err = e.message ?: "file send failed"
                DesktopChatLog.system(config.sessionId, err)
                mutateMessages(addressee.id) {
                    it + ChatMessage(
                        UUID.randomUUID().toString(),
                        MessageRole.System,
                        err,
                    )
                }
            }
        }
    }

    fun send(addressee: Addressee) {
        if (client.connectionState.value != ConnectionState.Connected) return
        val caption = drafts[addressee.id]?.text?.trim()?.ifBlank { null }
        val file = pendingAttach[addressee.id]
        if (file != null) {
            if (!file.isFile) {
                pendingAttach.remove(addressee.id)
                return
            }
            drafts[addressee.id] = TextFieldValue("")
            pendingAttach.remove(addressee.id)
            sendPendingFile(addressee, file, caption)
            return
        }
        val text = caption ?: return
        drafts[addressee.id] = TextFieldValue("")
        sendTextOnly(addressee, text)
    }

    fun pickAttachment(addressee: Addressee) {
        if (client.connectionState.value != ConnectionState.Connected) return
        val dialog = FileDialog(Frame(), "Attach file", FileDialog.LOAD)
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return
        val fileName = dialog.file ?: return
        val file = File(dir, fileName)
        if (!file.isFile) return
        pendingAttach[addressee.id] = file
    }

    fun startNewSession(addressee: Addressee) {
        if (waitingReply) return
        scope.launch {
            putMessages(addressee.id, emptyList())
            withContext(Dispatchers.IO) { DesktopChatHistoryStore.clear(addressee.id) }
            lastFailedOutbound = null
            runCatching {
                withContext(Dispatchers.IO) {
                    supabase().resolveOrCreateSession(
                        forceNew = true,
                        preferredId = config.sessionId,
                        senderName = AhccSupabaseClient.SENDER_DESKTOP,
                    )
                }
            }.onSuccess { resolved ->
                val next = config.copy(sessionId = resolved)
                config = next
                persistConfig(next)
            }.onFailure { e ->
                DesktopChatLog.system(config.sessionId, e.message ?: "New session id failed")
            }
            if (client.connectionState.value == ConnectionState.Connected) {
                sendTextOnly(addressee, "/new now", passthrough = true)
            } else {
                sendNewAfterConnectFor = addressee.id
                val bot = effectiveBot(addressee).ifBlank { config.telegramBotUsername }
                client.connect(config.copy(telegramBotUsername = bot))
                syncRegisteredBots()
            }
        }
    }

    fun resendLastFailed(addressee: Addressee) {
        if (waitingReply) return
        val pending = lastFailedOutbound ?: return
        val (addresseeId, body) = pending
        if (addresseeId != addressee.id) return
        sendTextOnly(Addressee.resolve(addressees, addresseeId), body)
    }

    fun onInlineButton(addresseeId: String, messageId: String, action: TelegramInlineAction) {
        if (waitingReply) return
        val addressee = Addressee.resolve(addressees, addresseeId)
        val msg = histories[addressee.id].orEmpty().find { it.id == messageId } ?: return
        val kb = msg.inlineKeyboard
        val label = kb?.rows?.flatten()
            ?.firstOrNull { it.action == action }
            ?.text
            ?: "✓"
        mutateMessages(addressee.id) { prev ->
            prev.map { if (it.id == messageId) it.copy(inlineKeyboard = null) else it }
        }
        awaitingAddresseeId = addressee.id
        waitingReply = true
        lastReplyComplete = false
        mutateMessages(addressee.id) { prev ->
            prev +
                ChatMessage(UUID.randomUUID().toString(), MessageRole.User, label) +
                ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, "", isStreaming = true)
        }
        DesktopChatLog.out(
            sessionId = config.sessionId,
            addresseeId = addressee.id,
            text = label,
            note = "inline",
        )
        scope.launch {
            try {
                if (config.channel == ChatChannel.Telegram) {
                    client.switchTelegramBot(effectiveBot(addressee))
                }
                client.handleInlineAction(action, kb)
            } catch (e: Exception) {
                waitingReply = false
                awaitingAddresseeId = null
                val err = e.message ?: "button failed"
                DesktopChatLog.system(config.sessionId, err)
                mutateMessages(addressee.id) { prev ->
                    prev.map {
                        if (it.isStreaming) it.copy(content = err, isStreaming = false, role = MessageRole.System)
                        else it
                    }
                }
            }
        }
    }

    private companion object {
        /** Max log lines kept in memory for the main window log pane. */
        const val LOG_CAP = 500

        /** Popup auto-hide delay. */
        const val NOTIFICATION_TTL_MS = 6_000L
    }
}
