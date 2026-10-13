package com.nous.ahcc.desktop.net

import com.nous.ahcc.desktop.model.ChatChannel
import com.nous.ahcc.desktop.model.ConnectionConfig
import com.nous.ahcc.desktop.model.ConnectionState
import com.nous.ahcc.desktop.model.HermesResponse
import com.nous.ahcc.desktop.model.TelegramInlineAction
import com.nous.ahcc.desktop.model.TelegramInlineKeyboard
import com.nous.ahcc.desktop.model.TelegramInlineKeyboardParser
import com.nous.ahcc.desktop.model.TelegramReply
import com.nous.ahcc.desktop.telegram.DesktopTelegramAuth
import com.nous.ahcc.desktop.telegram.DesktopTelegramUserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

/**
 * Routes chat to Telegram user session (AHCC path) or Inference HTTP SSE.
 */
class DesktopChatGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HermesHttpSseClient()
    val telegram = DesktopTelegramUserSession()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _responses = MutableSharedFlow<HermesResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<HermesResponse> = _responses.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var channel: ChatChannel = ChatChannel.Telegram
    private var collectJobs = mutableListOf<Job>()
    private var currentConfig: ConnectionConfig? = null

    fun connect(config: ConnectionConfig) {
        disconnect()
        currentConfig = config
        channel = config.channel
        collectJobs += scope.launch {
            when (channel) {
                ChatChannel.HttpSse -> {
                    http.connectionState.collect { _connectionState.value = it }
                }
                ChatChannel.Telegram -> {
                    telegram.auth.collect { auth ->
                        _connectionState.value = when (auth) {
                            is DesktopTelegramAuth.Ready -> ConnectionState.Connected
                            is DesktopTelegramAuth.Failed -> ConnectionState.Error
                            is DesktopTelegramAuth.Starting -> ConnectionState.Connecting
                            else -> ConnectionState.Connecting
                        }
                        if (auth is DesktopTelegramAuth.Failed) {
                            _lastError.value = auth.message
                        }
                    }
                }
            }
        }
        collectJobs += scope.launch {
            http.responses.collect { _responses.emit(it) }
        }
        collectJobs += scope.launch {
            http.lastError.collect { if (channel == ChatChannel.HttpSse) _lastError.value = it }
        }

        when (channel) {
            ChatChannel.HttpSse -> http.connect(config)
            ChatChannel.Telegram -> {
                _lastError.value = null
                telegram.begin(
                    apiId = config.telegramApiId,
                    apiHash = config.telegramApiHash,
                    phone = config.telegramPhone,
                    botUsername = config.telegramBotUsername,
                )
            }
        }
    }

    fun disconnect() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        http.disconnect()
        telegram.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun clearHistory() {
        http.clearHistory()
    }

    suspend fun sendMessage(text: String) {
        when (channel) {
            ChatChannel.HttpSse -> http.sendMessage(text)
            ChatChannel.Telegram -> emitTelegramReply(
                telegram.sendTextAndWaitReply(text) { partial ->
                    scope.launch {
                        _responses.emit(HermesResponse(event = "partial", content = partial))
                    }
                }
            )
        }
    }

    suspend fun sendMedia(envelopeText: String, historyPlaceholder: String) {
        when (channel) {
            ChatChannel.HttpSse -> http.sendMedia(envelopeText, historyPlaceholder)
            ChatChannel.Telegram -> {
                sendMessage(historyPlaceholder.ifBlank { envelopeText.take(500) })
            }
        }
    }

    suspend fun handleInlineAction(action: TelegramInlineAction, keyboard: TelegramInlineKeyboard?) {
        when (action) {
            is TelegramInlineAction.SendText -> sendMessage(action.text)
            is TelegramInlineAction.Url -> {
                runCatching { Desktop.getDesktop().browse(URI(action.url)) }
                    .onFailure { e ->
                        _responses.emit(
                            HermesResponse(event = "error", error = e.message ?: "Could not open link")
                        )
                    }
            }
            is TelegramInlineAction.Callback -> {
                val kb = keyboard
                if (kb == null || kb.messageId == 0L) {
                    _responses.emit(
                        HermesResponse(event = "error", error = "Missing Telegram button data (need live markup)")
                    )
                    return
                }
                _responses.emit(HermesResponse(event = "typing"))
                emitTelegramReply(
                    telegram.pressInlineCallback(kb.chatId, kb.messageId, action.data) { partial ->
                        scope.launch {
                            _responses.emit(HermesResponse(event = "partial", content = partial))
                        }
                    }
                )
            }
        }
    }

    private suspend fun emitTelegramReply(reply: TelegramReply) {
        val live = reply.keyboard?.takeIf { !it.isEmpty }
            ?: telegram.takePendingInlineKeyboard()?.takeIf { !it.isEmpty }
        val fallback = live ?: TelegramInlineKeyboardParser.fromTextFallback(reply.text)
        val display = if (fallback != null) {
            TelegramInlineKeyboardParser.stripFallbackLine(reply.text)
        } else {
            reply.text
        }
        _responses.emit(
            HermesResponse(
                event = "done",
                content = display,
                inlineKeyboard = fallback,
            )
        )
    }
}
