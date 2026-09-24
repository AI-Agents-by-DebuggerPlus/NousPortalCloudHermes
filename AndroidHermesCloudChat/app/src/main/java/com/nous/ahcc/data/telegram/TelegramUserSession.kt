package com.nous.ahcc.data.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.nous.ahcc.BuildConfig
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.nous.ahcc.domain.model.AgentReplyParts
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class TelegramUserAuth {
    data object Starting : TelegramUserAuth()
    data object NeedPhone : TelegramUserAuth()
    data object NeedCode : TelegramUserAuth()
    data class NeedPassword(val hint: String) : TelegramUserAuth()
    data object Ready : TelegramUserAuth()
    data class Failed(val message: String) : TelegramUserAuth()
}

/**
 * Telegram user session (MTProto via TDLib). Independent of the official Telegram app.
 * The bot token is not used. Hermes keeps the only getUpdates poll.
 */
class TelegramUserSession(context: Context) {
    private val databaseDir = File(context.filesDir, "tdlib").apply { mkdirs() }.absolutePath
    private var client: Client? = null

    private val _auth = MutableStateFlow<TelegramUserAuth>(TelegramUserAuth.Starting)
    val auth: StateFlow<TelegramUserAuth> = _auth.asStateFlow()

    private var parametersSent = false
    private var botChatId: Long = 0L
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val replyLock = Any()
    private val replyParts = linkedMapOf<Long, String>()
    private val outgoingIds = mutableSetOf<Long>()
    @Volatile private var replyWaiter: CompletableDeferred<String>? = null
    @Volatile private var onReply: ((String) -> Unit)? = null
    private var quietJob: Job? = null

    var apiId: Int = 0
    var apiHash: String = ""

    fun restoreSavedSession(apiId: Int, apiHash: String) {
        if (apiId == 0 || apiHash.isBlank()) return
        val saved = File(databaseDir).walkTopDown().any { it.isFile && it.length() > 0L }
        if (!saved) {
            Log.i(TAG, "no saved Telegram session")
            return
        }
        Log.i(TAG, "restoring Telegram session")
        begin(apiId, apiHash)
    }

    fun begin(apiId: Int, apiHash: String) {
        this.apiId = apiId
        this.apiHash = apiHash.trim()
        if (client == null) {
            parametersSent = false
            client = Client.create(
                { update -> onUpdate(update) },
                { error -> Log.e(TAG, "update exception", error) },
                { error -> Log.e(TAG, "default exception", error) }
            )
        } else if (_auth.value is TelegramUserAuth.Failed || _auth.value is TelegramUserAuth.NeedPhone) {
            parametersSent = false
            sendParameters()
        }
    }

    fun submitPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null), ::logResult)
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code.trim()), ::logResult)
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password), ::logResult)
    }

    val isReady: Boolean get() = _auth.value is TelegramUserAuth.Ready

    suspend fun sendTextAndWaitReply(
        text: String,
        botUsername: String,
        onUpdate: (String) -> Unit = {},
    ): String = sendAndWait(botUsername, onUpdate) {
            TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        }

    suspend fun sendAudioAndWaitReply(
        path: String,
        durationMs: Long,
        botUsername: String,
        caption: String,
        onUpdate: (String) -> Unit = {},
    ): String = sendAndWait(botUsername, onUpdate) {
        TdApi.InputMessageAudio(
            TdApi.InputAudio(
                TdApi.InputFileLocal(path),
                null,
                (durationMs / 1000L).toInt().coerceAtLeast(1),
                "voice",
                "AHCC"
            ),
            TdApi.FormattedText(caption, emptyArray())
        )
    }

    private suspend fun sendAndWait(
        botUsername: String,
        onUpdate: (String) -> Unit,
        content: () -> TdApi.InputMessageContent,
    ): String = withContext(Dispatchers.IO) {
        if (!isReady) error("Сначала войдите в Telegram как пользователь")
        val chatId = ensureBotChat(botUsername)
        val waiter = CompletableDeferred<String>()
        synchronized(replyLock) {
            replyParts.clear()
            outgoingIds.clear()
        }
        quietJob?.cancel()
        onReply = onUpdate
        replyWaiter = waiter
        try {
            val sent = sendExpect<TdApi.Message>(
                TdApi.SendMessage(chatId, null, null, null, null, content())
            )
            synchronized(replyLock) { outgoingIds.add(sent.id) }
            Log.i(TAG, "sent to @$botUsername chat=$chatId")
            try {
                withTimeout(WAIT_MS) { waiter.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                val partial = snapshotForUi()
                if (partial.isBlank()) error("Нет текстового ответа за 3 минуты")
                partial
            }
        } finally {
            replyWaiter = null
            onReply = null
            quietJob?.cancel()
        }
    }

    private suspend fun ensureBotChat(username: String): Long {
        if (botChatId != 0L) return botChatId
        val chat = sendExpect<TdApi.Chat>(TdApi.SearchPublicChat(username.removePrefix("@")))
        botChatId = chat.id
        return botChatId
    }

    private fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuth(update.authorizationState)
            is TdApi.UpdateNewMessage -> onIncoming(update.message)
            is TdApi.UpdateMessageContent ->
                acceptText(update.chatId, update.messageId, textOf(update.newContent))
        }
    }

    private fun onAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber ->
                _auth.value = TelegramUserAuth.NeedPhone
            is TdApi.AuthorizationStateWaitCode ->
                _auth.value = TelegramUserAuth.NeedCode
            is TdApi.AuthorizationStateWaitPassword ->
                _auth.value = TelegramUserAuth.NeedPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                Log.i(TAG, "user session ready")
                _auth.value = TelegramUserAuth.Ready
            }
            is TdApi.AuthorizationStateClosed ->
                _auth.value = TelegramUserAuth.Failed("Сеанс Telegram закрыт")
            is TdApi.AuthorizationStateLoggingOut ->
                _auth.value = TelegramUserAuth.Starting
            else -> Log.i(TAG, "auth ${state.javaClass.simpleName}")
        }
    }

    private fun sendParameters() {
        if (parametersSent) return
        if (apiId == 0 || apiHash.isBlank()) {
            _auth.value = TelegramUserAuth.Failed("Нужны api_id и api_hash с my.telegram.org/apps")
            return
        }
        parametersSent = true
        val parameters = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = databaseDir
            filesDirectory = databaseDir
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = this@TelegramUserSession.apiId
            apiHash = this@TelegramUserSession.apiHash
            systemLanguageCode = "en"
            deviceModel = Build.MODEL ?: "Android"
            systemVersion = Build.VERSION.RELEASE ?: "14"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        client?.send(parameters, ::logResult)
    }

    private fun onIncoming(message: TdApi.Message) {
        if (message.isOutgoing) {
            synchronized(replyLock) {
                outgoingIds.add(message.id)
                replyParts.remove(message.id)
            }
            return
        }
        val text = textOf(message.content)
        if (text == null && replyWaiter != null) {
            Log.i(TAG, "skip ${message.content.javaClass.simpleName}")
        }
        acceptText(message.chatId, message.id, text)
    }

    /**
     * Shell and tool cards arrive first. The transcript comes later as its own message.
     * Tool lines must not close the wait.
     */
    private fun acceptText(chatId: Long, messageId: Long, raw: String?) {
        if (botChatId != 0L && chatId != botChatId) return
        if (synchronized(replyLock) { messageId in outgoingIds }) return
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || AgentReplyParts.isOwnEcho(text)) return
        val waiter = replyWaiter ?: return
        val shown = synchronized(replyLock) {
            replyParts[messageId] = text
            visibleReply()
        }
        Log.i(TAG, "reply update chars=${shown.length}")
        if (shown.isNotEmpty()) onReply?.invoke(shown)
        val hasAnswer = synchronized(replyLock) {
            replyParts.values.any { AgentReplyParts.containsAnswer(it) }
        }
        quietJob?.cancel()
        if (!hasAnswer) return
        quietJob = sessionScope.launch {
            delay(ANSWER_QUIET_MS)
            val latest = snapshotForUi()
            if (replyWaiter !== waiter || waiter.isCompleted || latest.isBlank()) return@launch
            waiter.complete(latest)
        }
    }

    private fun snapshotForUi(): String = synchronized(replyLock) { visibleReply() }

    private fun visibleReply(): String =
        replyParts.values
            .map { it.trim() }
            .filter { it.isNotEmpty() && !AgentReplyParts.isOwnEcho(it) }
            .joinToString("\n")

    private fun textOf(content: TdApi.MessageContent): String? = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessageAudio -> content.caption?.text
        is TdApi.MessageDocument -> content.caption?.text
        is TdApi.MessagePhoto -> content.caption?.text
        is TdApi.MessageVideo -> content.caption?.text
        is TdApi.MessageVoiceNote -> content.caption?.text
        else -> null
    }

    private fun logResult(result: TdApi.Object) {
        if (result is TdApi.Error) {
            Log.e(TAG, "td error ${result.code}: ${result.message}")
            _auth.value = TelegramUserAuth.Failed(result.message)
        }
    }

    private suspend fun <T : TdApi.Object> sendExpect(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            val current = client ?: run {
                cont.resumeWithException(IllegalStateException("Telegram session is not started"))
                return@suspendCancellableCoroutine
            }
            current.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(IllegalStateException(result.message))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }

    companion object {
        private const val TAG = "AHCC-TG-User"
        private const val ANSWER_QUIET_MS = 15_000L
        private const val WAIT_MS = 180_000L
    }
}
