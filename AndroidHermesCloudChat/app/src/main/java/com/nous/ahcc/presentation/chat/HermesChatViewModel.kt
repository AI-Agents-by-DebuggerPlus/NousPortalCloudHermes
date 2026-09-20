package com.nous.ahcc.presentation.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nous.ahcc.data.HermesChatGateway
import com.nous.ahcc.data.local.ConnectionPreferences
import com.nous.ahcc.domain.model.ChatMessage
import com.nous.ahcc.domain.model.ConnectionConfig
import com.nous.ahcc.domain.model.ConnectionState
import com.nous.ahcc.domain.model.MessageRole
import com.nous.ahcc.domain.model.TransportMode
import com.nous.ahcc.service.HermesConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val config: ConnectionConfig = ConnectionConfig(),
    val draft: String = "",
    val lastError: String? = null,
    val isSending: Boolean = false
)

class HermesChatViewModel(
    application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.configFlow.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        viewModelScope.launch {
            chatGateway.connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
        viewModelScope.launch {
            chatGateway.lastError.collect { error ->
                _uiState.update { it.copy(lastError = error) }
            }
        }
        viewModelScope.launch {
            chatGateway.responses.collect { response ->
                handleIncoming(response)
            }
        }
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun updateConfig(config: ConnectionConfig) {
        viewModelScope.launch {
            preferences.save(config)
        }
    }

    fun connect() {
        val config = _uiState.value.config
        if (config.keepAliveInBackground && config.transport == TransportMode.WebSocket) {
            HermesConnectionService.start(getApplication())
        }
        chatGateway.connect(config)
    }

    fun disconnect() {
        HermesConnectionService.stop(getApplication())
        chatGateway.disconnect()
    }

    /** Send user prompt and prepare a streaming assistant bubble. */
    fun sendPrompt(userPrompt: String = _uiState.value.draft.trim()) {
        if (userPrompt.isBlank()) return
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            _uiState.update { it.copy(lastError = "Not connected") }
            return
        }

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.User,
            content = userPrompt
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            isStreaming = true
        )

        _uiState.update {
            it.copy(
                draft = "",
                isSending = true,
                messages = it.messages + userMsg + assistantPlaceholder
            )
        }

        viewModelScope.launch {
            try {
                chatGateway.sendMessage(userPrompt, _uiState.value.config.sessionId)
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isSending = false,
                        lastError = e.message,
                        messages = state.messages.map { msg ->
                            if (msg.id == assistantId) {
                                msg.copy(
                                    content = "Failed to send: ${e.message}",
                                    isStreaming = false,
                                    role = MessageRole.System
                                )
                            } else msg
                        }
                    )
                }
            }
        }
    }

    private fun handleIncoming(response: com.nous.ahcc.domain.model.HermesResponse) {
        when (response.event) {
            "token", "message.delta" -> {
                val delta = response.delta ?: response.content.orEmpty()
                if (delta.isEmpty()) return
                appendToStreamingAssistant(delta)
            }
            "tool_call" -> {
                val name = response.name
                    ?: response.tool_call?.name
                    ?: "tool"
                val args = response.args ?: response.tool_call?.args
                val argsText = args?.entries?.joinToString { "${it.key}=${it.value}" }.orEmpty()
                val toolMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Tool,
                    content = if (argsText.isBlank()) "Calling $name…" else "Calling $name($argsText)",
                    toolName = name
                )
                _uiState.update { it.copy(messages = it.messages + toolMsg) }
            }
            "done", "message.complete" -> {
                finalizeStreamingAssistant()
                response.session_id?.let { sid ->
                    if (sid != _uiState.value.config.sessionId) {
                        updateConfig(_uiState.value.config.copy(sessionId = sid))
                    }
                }
            }
            "error" -> {
                finalizeStreamingAssistant()
                _uiState.update {
                    it.copy(
                        lastError = response.error ?: "Agent error",
                        messages = it.messages + ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = MessageRole.System,
                            content = response.error ?: "Agent error"
                        )
                    )
                }
            }
            "message" -> {
                val content = response.content
                    ?: response.data?.content
                    ?: response.delta
                    ?: return
                // Full message (non-streamed) — replace streaming bubble or append
                val hasStreaming = _uiState.value.messages.any { it.isStreaming }
                if (hasStreaming) {
                    replaceStreamingContent(content)
                    finalizeStreamingAssistant()
                } else {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = MessageRole.Assistant,
                                content = content
                            )
                        )
                    }
                }
            }
            else -> {
                // Unknown event: if delta present, treat as stream token
                val delta = response.delta
                if (!delta.isNullOrEmpty()) appendToStreamingAssistant(delta)
            }
        }
    }

    private fun appendToStreamingAssistant(delta: String) {
        _uiState.update { state ->
            val msgs = state.messages.toMutableList()
            val idx = msgs.indexOfLast { it.role == MessageRole.Assistant && it.isStreaming }
            if (idx >= 0) {
                val current = msgs[idx]
                msgs[idx] = current.copy(content = current.content + delta)
            } else {
                msgs += ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = MessageRole.Assistant,
                    content = delta,
                    isStreaming = true
                )
            }
            state.copy(messages = msgs, isSending = true)
        }
    }

    private fun replaceStreamingContent(content: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.isStreaming && msg.role == MessageRole.Assistant) {
                        msg.copy(content = content)
                    } else msg
                }
            )
        }
    }

    private fun finalizeStreamingAssistant() {
        _uiState.update { state ->
            state.copy(
                isSending = false,
                messages = state.messages.map { msg ->
                    if (msg.isStreaming) msg.copy(isStreaming = false) else msg
                }
            )
        }
    }

    fun clearChat() {
        chatGateway.clearHttpHistory()
        _uiState.update { it.copy(messages = emptyList(), lastError = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
    }
}

class HermesChatViewModelFactory(
    private val application: Application,
    private val preferences: ConnectionPreferences,
    private val chatGateway: HermesChatGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HermesChatViewModel::class.java)) {
            return HermesChatViewModel(application, preferences, chatGateway) as T
        }
        throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
