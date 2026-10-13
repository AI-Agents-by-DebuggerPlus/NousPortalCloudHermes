package com.nous.ahcc.desktop.telegram

import com.nous.ahcc.desktop.log.DesktopChatLog
import com.nous.ahcc.desktop.model.TelegramInlineAction
import com.nous.ahcc.desktop.model.TelegramInlineButton
import com.nous.ahcc.desktop.model.TelegramInlineKeyboard
import com.nous.ahcc.desktop.model.TelegramReply
import it.tdlight.Init
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import it.tdlight.client.SimpleAuthenticationSupplier
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Desktop Telegram user session via TDLight (same idea as Android TDLib AHCC).
 */
class DesktopTelegramUserSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _auth = MutableStateFlow<DesktopTelegramAuth>(DesktopTelegramAuth.Starting)
    val auth: StateFlow<DesktopTelegramAuth> = _auth.asStateFlow()

    private var factory: SimpleTelegramClientFactory? = null
    private var client: SimpleTelegramClient? = null
    private var botChatId: Long = 0L
    private var botUsername: String = ""

    private val phoneRef = AtomicReference("")
    private val codeWaiter = AtomicReference<CompletableDeferred<String>?>(null)
    private val passwordWaiter = AtomicReference<CompletableDeferred<String>?>(null)

    private val replyLock = Any()
    private val replyParts = linkedMapOf<Long, String>()
    private val replyMarkups = linkedMapOf<Long, TelegramInlineKeyboard>()
    private val outgoingIds = ConcurrentHashMap.newKeySet<Long>()
    private var replyWaiter: CompletableDeferred<TelegramReply>? = null
    private var onReply: ((String) -> Unit)? = null
    private var quietJob: Job? = null
    private var pendingInlineKeyboard: TelegramInlineKeyboard? = null
    /** After a turn completes, keep merging late bot messages into the UI for a while. */
    private var idleAppend: ((String, TelegramInlineKeyboard?) -> Unit)? = null
    private var idleAppendUntilMs: Long = 0L

    val isReady: Boolean get() = _auth.value is DesktopTelegramAuth.Ready

    fun setIdleAppend(handler: ((String, TelegramInlineKeyboard?) -> Unit)?) {
        idleAppend = handler
    }

    fun takePendingInlineKeyboard(): TelegramInlineKeyboard? {
        val kb = pendingInlineKeyboard
        pendingInlineKeyboard = null
        return kb
    }

    fun begin(apiId: Int, apiHash: String, phone: String, botUsername: String) {
        if (apiId == 0 || apiHash.isBlank()) {
            _auth.value = DesktopTelegramAuth.Failed("api_id and api_hash required (Credentials)")
            return
        }
        if (phone.isBlank()) {
            _auth.value = DesktopTelegramAuth.NeedPhone
            return
        }
        when (_auth.value) {
            is DesktopTelegramAuth.NeedCode,
            is DesktopTelegramAuth.NeedPassword,
            is DesktopTelegramAuth.Ready,
            -> {
                this.botUsername = botUsername.removePrefix("@")
                phoneRef.set(phone.trim())
                return
            }
            else -> Unit
        }
        this.botUsername = botUsername.removePrefix("@")
        phoneRef.set(phone.trim())
        scope.launch { startClient(apiId, apiHash, phone.trim()) }
    }

    fun submitPhone(phone: String) {
        val trimmed = phone.trim()
        phoneRef.set(trimmed)
        if (_auth.value is DesktopTelegramAuth.NeedPhone) {
            client?.send(TdApi.SetAuthenticationPhoneNumber(trimmed, null)) { }
        }
    }

    fun submitCode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        codeWaiter.getAndSet(null)?.complete(trimmed)
        val c = client
        if (c == null) {
            _auth.value = DesktopTelegramAuth.Failed("Client not ready yet — Connect, wait for the code, then Submit")
            return
        }
        c.send(TdApi.CheckAuthenticationCode(trimmed)) { }
    }

    fun submitPassword(password: String) {
        val trimmed = password
        passwordWaiter.getAndSet(null)?.complete(trimmed)
        client?.send(TdApi.CheckAuthenticationPassword(trimmed)) { }
    }

    fun disconnect() {
        quietJob?.cancel()
        replyWaiter?.cancel()
        runCatching { client?.close() }
        runCatching { factory?.close() }
        client = null
        factory = null
        botChatId = 0L
        _auth.value = DesktopTelegramAuth.Starting
    }

    suspend fun sendTextAndWaitReply(
        text: String,
        onUpdate: (String) -> Unit = {},
    ): TelegramReply = withContext(Dispatchers.IO) {
        val c = client ?: error("Telegram is not connected")
        if (!isReady) error("Sign in to Telegram first (Credentials)")
        val chatId = ensureBotChat(c)
        val waiter = CompletableDeferred<TelegramReply>()
        synchronized(replyLock) {
            replyParts.clear()
            replyMarkups.clear()
            outgoingIds.clear()
        }
        pendingInlineKeyboard = null
        quietJob?.cancel()
        onReply = onUpdate
        replyWaiter = waiter
        try {
            val content = TdApi.InputMessageText(
                TdApi.FormattedText(text, emptyArray()),
                null,
                false,
            )
            val sent = c.send(TdApi.SendMessage(chatId, null, null, null, null, content)).get()
            if (sent is TdApi.Message) {
                outgoingIds.add(sent.id)
            }
            try {
                withTimeout(WAIT_MS) { waiter.await() }
            } catch (_: Exception) {
                val partial = snapshot()
                if (partial.isBlank()) error("No text reply within 3 minutes")
                TelegramReply(partial, latestKeyboard())
            }
        } finally {
            pendingInlineKeyboard = synchronized(replyLock) { latestKeyboard() }
            replyWaiter = null
            onReply = null
            quietJob?.cancel()
            idleAppendUntilMs = System.currentTimeMillis() + IDLE_APPEND_MS
        }
    }

    suspend fun pressInlineCallback(
        chatId: Long,
        messageId: Long,
        data: ByteArray,
        onUpdate: (String) -> Unit = {},
    ): TelegramReply = withContext(Dispatchers.IO) {
        val c = client ?: error("Telegram is not connected")
        if (!isReady) error("Sign in to Telegram first (Credentials)")
        val resolvedChat = if (chatId != 0L) chatId else ensureBotChat(c)
        val waiter = CompletableDeferred<TelegramReply>()
        synchronized(replyLock) {
            replyParts.clear()
            replyMarkups.clear()
            outgoingIds.clear()
        }
        quietJob?.cancel()
        onReply = onUpdate
        replyWaiter = waiter
        try {
            c.send(
                TdApi.GetCallbackQueryAnswer(
                    resolvedChat,
                    messageId,
                    TdApi.CallbackQueryPayloadData(data),
                )
            ).get()
            try {
                withTimeout(WAIT_MS) { waiter.await() }
            } catch (_: Exception) {
                val partial = snapshot()
                if (partial.isBlank()) error("No reply after button press")
                TelegramReply(partial, latestKeyboard())
            }
        } finally {
            pendingInlineKeyboard = synchronized(replyLock) { latestKeyboard() }
            replyWaiter = null
            onReply = null
            quietJob?.cancel()
            idleAppendUntilMs = System.currentTimeMillis() + IDLE_APPEND_MS
        }
    }

    private suspend fun startClient(apiId: Int, apiHash: String, phone: String) {
        try {
            Init.init()
            disconnectSoft()
            _auth.value = DesktopTelegramAuth.Starting
            val db = Path.of(System.getProperty("user.home"), ".ahcc-desktop", "tdlib")
            Files.createDirectories(db)
            val f = SimpleTelegramClientFactory()
            factory = f
            val settings = TDLibSettings.create(APIToken(apiId, apiHash))
            settings.databaseDirectoryPath = db
            settings.downloadedFilesDirectoryPath = db.resolve("files")
            val builder = f.builder(settings)
            builder.setClientInteraction(UiClientInteraction())
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { upd ->
                onAuth(upd.authorizationState)
            }
            builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { upd ->
                onIncoming(upd.message)
            }
            builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java) { upd ->
                logRawTelegram(upd.chatId, upd.messageId, upd.newContent, note = "edited")
                acceptText(upd.chatId, upd.messageId, textOf(upd.newContent), keyboard = null)
            }
            val authSupplier: SimpleAuthenticationSupplier<*> = AuthenticationSupplier.user(phone)
            client = builder.build(authSupplier)
        } catch (e: Exception) {
            _auth.value = DesktopTelegramAuth.Failed(e.message ?: "Telegram start failed")
        }
    }

    private fun disconnectSoft() {
        runCatching { client?.close() }
        runCatching { factory?.close() }
        client = null
        factory = null
    }

    private fun onAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _auth.value = DesktopTelegramAuth.NeedPhone
            is TdApi.AuthorizationStateWaitCode ->
                _auth.value = DesktopTelegramAuth.NeedCode
            is TdApi.AuthorizationStateWaitPassword ->
                _auth.value = DesktopTelegramAuth.NeedPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                _auth.value = DesktopTelegramAuth.Ready
                scope.launch {
                    runCatching { client?.let { ensureBotChat(it) } }
                }
            }
            is TdApi.AuthorizationStateClosed ->
                _auth.value = DesktopTelegramAuth.Failed("Telegram session closed")
            else -> Unit
        }
    }

    private fun onIncoming(message: TdApi.Message) {
        if (message.isOutgoing) {
            outgoingIds.add(message.id)
            return
        }
        logRawTelegram(message.chatId, message.id, message.content, note = "new")
        val keyboard = parseInlineKeyboard(message)
        if (keyboard != null) {
            synchronized(replyLock) { replyMarkups[message.id] = keyboard }
        }
        acceptText(message.chatId, message.id, textOf(message.content), keyboard)
    }

    /** Persist TDLib plain text + entities exactly as received (before UI heuristics). */
    private fun logRawTelegram(
        chatId: Long,
        messageId: Long,
        content: TdApi.MessageContent?,
        note: String,
    ) {
        if (botChatId != 0L && chatId != botChatId) return
        val (kind, ft) = formattedOf(content) ?: return
        val plain = ft.text.orEmpty()
        if (plain.isEmpty() && (ft.entities == null || ft.entities.isEmpty())) return
        DesktopChatLog.telegramRaw(
            chatId = chatId,
            messageId = messageId,
            contentKind = kind,
            plainText = plain,
            entitiesDump = dumpEntities(plain, ft.entities),
            markdownProjection = formatFormattedText(ft).orEmpty(),
            note = note,
        )
    }

    private fun formattedOf(content: TdApi.MessageContent?): Pair<String, TdApi.FormattedText>? {
        return when (content) {
            is TdApi.MessageText -> content.text?.let { "MessageText" to it }
            is TdApi.MessagePhoto -> content.caption?.let { "MessagePhoto" to it }
            is TdApi.MessageDocument -> content.caption?.let { "MessageDocument" to it }
            is TdApi.MessageAnimation -> content.caption?.let { "MessageAnimation" to it }
            else -> null
        }
    }

    private fun dumpEntities(plain: String, entities: Array<TdApi.TextEntity>?): String {
        val list = entities?.filterNotNull().orEmpty()
        if (list.isEmpty()) return "(none)"
        return buildString {
            appendLine("count=${list.size} plainUtf16Len=${plain.length}")
            list.forEachIndexed { i, e ->
                val type = e.type
                val typeName = type?.javaClass?.simpleName?.removePrefix("TextEntityType") ?: "?"
                val extra = when (type) {
                    is TdApi.TextEntityTypePreCode ->
                        " language=\"${type.language.orEmpty().replace("\"", "\\\"")}\""
                    is TdApi.TextEntityTypeTextUrl ->
                        " url=\"${type.url.orEmpty()}\""
                    is TdApi.TextEntityTypeMentionName ->
                        " userId=${type.userId}"
                    is TdApi.TextEntityTypeCustomEmoji ->
                        " customEmojiId=${type.customEmojiId}"
                    else -> ""
                }
                val start = e.offset.coerceAtLeast(0)
                val end = (e.offset + e.length).coerceAtMost(plain.length)
                val snippet = if (start < end) {
                    plain.substring(start, end).replace("\n", "\\n").take(120)
                } else {
                    ""
                }
                appendLine(
                    "[$i] offset=${e.offset} length=${e.length} type=$typeName$extra snippet=\"$snippet\""
                )
            }
        }.trimEnd()
    }

    private fun acceptText(
        chatId: Long,
        messageId: Long,
        raw: String?,
        keyboard: TelegramInlineKeyboard?,
    ) {
        if (botChatId != 0L && chatId != botChatId) return
        if (messageId in outgoingIds) return
        val text = raw?.trim().orEmpty()
        val waiter = replyWaiter
        if (waiter == null) {
            if (keyboard != null) pendingInlineKeyboard = keyboard
            if (text.isNotEmpty() && System.currentTimeMillis() < idleAppendUntilMs) {
                idleAppend?.invoke(text, keyboard)
            }
            return
        }
        if (text.isEmpty() && keyboard == null) return
        val shown = synchronized(replyLock) {
            if (text.isNotEmpty()) replyParts[messageId] = text
            replyParts.values.joinToString("\n\n")
        }
        if (shown.isNotEmpty()) onReply?.invoke(shown)
        val hasKeyboard = keyboard != null || synchronized(replyLock) { replyMarkups.isNotEmpty() }
        quietJob?.cancel()
        val quietMs = when {
            hasKeyboard && shown.isNotEmpty() -> KEYBOARD_QUIET_MS
            looksLikeToolOnly(shown) -> TOOL_QUIET_MS
            else -> QUIET_MS
        }
        if (shown.isBlank() && !hasKeyboard) return
        quietJob = scope.launch {
            delay(quietMs)
            if (replyWaiter !== waiter || waiter.isCompleted) return@launch
            val latest = snapshot()
            val kb = latestKeyboard()
            if (latest.isBlank() && kb == null) return@launch
            // Still only tool noise — wait one more tool window before finishing.
            if (kb == null && looksLikeToolOnly(latest)) {
                delay(TOOL_QUIET_MS)
                if (replyWaiter !== waiter || waiter.isCompleted) return@launch
            }
            waiter.complete(TelegramReply(snapshot().ifBlank { latest }, latestKeyboard() ?: kb))
        }
    }

    private fun looksLikeToolOnly(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        val toolish = lines.count { line ->
            line.startsWith("💻") || line.startsWith("✍️") || line.startsWith("📖") ||
                line.startsWith("📚") || line.startsWith("🔧") || line.startsWith("↪") ||
                line.contains("Writing ", ignoreCase = true) ||
                line.contains("Reading ", ignoreCase = true) ||
                line.startsWith("terminal", ignoreCase = true) ||
                line.startsWith("/opt/") || line.startsWith("date +") ||
                line.startsWith("ls ") || line.startsWith("grep ") ||
                line.startsWith("find ") || line.startsWith("echo ")
        }
        return toolish * 2 >= lines.size
    }

    private fun latestKeyboard(): TelegramInlineKeyboard? =
        synchronized(replyLock) { replyMarkups.entries.lastOrNull()?.value }

    private fun snapshot(): String = synchronized(replyLock) {
        replyParts.values.joinToString("\n\n")
    }

    private fun parseInlineKeyboard(message: TdApi.Message): TelegramInlineKeyboard? {
        val markup = message.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard ?: return null
        val rows = markup.rows?.mapNotNull { row ->
            val buttons = row?.mapNotNull { btn ->
                if (btn == null) return@mapNotNull null
                val label = btn.text?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                val action = when (val t = btn.type) {
                    is TdApi.InlineKeyboardButtonTypeCallback ->
                        TelegramInlineAction.Callback(t.data ?: return@mapNotNull null)
                    is TdApi.InlineKeyboardButtonTypeUrl ->
                        TelegramInlineAction.Url(t.url?.trim().orEmpty().ifEmpty { return@mapNotNull null })
                    else -> return@mapNotNull null
                }
                TelegramInlineButton(text = label, action = action)
            }.orEmpty()
            buttons.takeIf { it.isNotEmpty() }
        }.orEmpty()
        if (rows.isEmpty()) return null
        return TelegramInlineKeyboard(chatId = message.chatId, messageId = message.id, rows = rows)
    }

    private suspend fun ensureBotChat(c: SimpleTelegramClient): Long {
        if (botChatId != 0L) return botChatId
        val username = botUsername.ifBlank { error("Empty bot username") }
        val chat = c.send(TdApi.SearchPublicChat(username)).get() as TdApi.Chat
        botChatId = chat.id
        return botChatId
    }

    private fun textOf(content: TdApi.MessageContent?): String? = when (content) {
        is TdApi.MessageText -> formatFormattedText(content.text)
        is TdApi.MessagePhoto -> formatFormattedText(content.caption)
        is TdApi.MessageDocument -> formatFormattedText(content.caption)
        is TdApi.MessageAnimation -> formatFormattedText(content.caption)
        else -> null
    }

    /**
     * Preserve Telegram pre/code entities as markdown fences.
     * PreCode.language becomes the purple title in Telegram (e.g. "Tony Stark").
     * Offsets/lengths are UTF-16 code units (same as Java String).
     */
    private fun formatFormattedText(ft: TdApi.FormattedText?): String? {
        val raw = ft?.text ?: return null
        val entities = ft.entities?.filterNotNull().orEmpty()
        if (entities.isEmpty()) return raw

        data class PreSpan(val start: Int, val end: Int, val language: String)

        val spans = entities.mapNotNull { entity ->
            val lang = when (val t = entity.type) {
                is TdApi.TextEntityTypePreCode -> t.language?.trim().orEmpty()
                is TdApi.TextEntityTypePre -> ""
                else -> return@mapNotNull null
            }
            val start = entity.offset
            val end = entity.offset + entity.length
            if (start < 0 || end > raw.length || start >= end) return@mapNotNull null
            PreSpan(start, end, lang)
        }.sortedBy { it.start }
        if (spans.isEmpty()) return raw

        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.start < cursor) continue
            sb.append(raw, cursor, span.start)
            val body = raw.substring(span.start, span.end).trimEnd('\n')
            if (span.language.isNotEmpty()) {
                sb.append("```").append(span.language).append('\n')
            } else {
                sb.append("```\n")
            }
            sb.append(body).append("\n```")
            cursor = span.end
        }
        if (cursor < raw.length) sb.append(raw, cursor, raw.length)
        return sb.toString()
    }

    private inner class UiClientInteraction : ClientInteraction {
        override fun onParameterRequest(
            parameter: InputParameter,
            parameterInfo: ParameterInfo,
        ): CompletableFuture<String> {
            return when (parameter) {
                InputParameter.ASK_CODE -> {
                    _auth.value = DesktopTelegramAuth.NeedCode
                    val d = CompletableDeferred<String>()
                    codeWaiter.set(d)
                    CompletableFuture.supplyAsync { runBlocking { d.await() } }
                }
                InputParameter.ASK_PASSWORD -> {
                    _auth.value = DesktopTelegramAuth.NeedPassword(parameterInfo.toString())
                    val d = CompletableDeferred<String>()
                    passwordWaiter.set(d)
                    CompletableFuture.supplyAsync { runBlocking { d.await() } }
                }
                else -> CompletableFuture.completedFuture(phoneRef.get())
            }
        }
    }

    companion object {
        private const val WAIT_MS = 300_000L
        private const val QUIET_MS = 15_000L
        private const val TOOL_QUIET_MS = 35_000L
        private const val KEYBOARD_QUIET_MS = 2_500L
        private const val IDLE_APPEND_MS = 90_000L
    }
}
