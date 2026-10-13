package com.nous.ahcc.domain.model

/**
 * Telegram-style inline buttons under an assistant bubble.
 * Live keyboards come from TDLib [replyMarkup]; text fallbacks recreate
 * Hermes prompts like `/approve` when markup was not delivered.
 */
data class TelegramInlineKeyboard(
    val chatId: Long,
    val messageId: Long,
    val rows: List<List<TelegramInlineButton>>,
) {
    val isEmpty: Boolean get() = rows.isEmpty() || rows.all { it.isEmpty() }
}

data class TelegramInlineButton(
    val text: String,
    val action: TelegramInlineAction,
)

sealed class TelegramInlineAction {
    /** Real Bot API callback — press via TDLib GetCallbackQueryAnswer. */
    data class Callback(val data: ByteArray) : TelegramInlineAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Callback) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** Open URL (rare for Hermes confirms). */
    data class Url(val url: String) : TelegramInlineAction()

    /** Synthetic: send this text to the bot (text-fallback path). */
    data class SendText(val text: String) : TelegramInlineAction()
}

object TelegramInlineKeyboardParser {
    private val fallbackLine = Regex(
        """(?i)_?\s*text\s+fallback\s*:\s*reply\s+(/[\w]+)(?:\s*,\s*(/[\w]+))*\s*(?:or\s+(/[\w]+))?\s*\.?_?"""
    )
    private val slashToken = Regex("""/[\w]+""")

    /** Labels Hermes uses on /new confirm (and similar). */
    private val knownLabels = mapOf(
        "/approve" to "✅ Approve Once",
        "/always" to "🔒 Always Approve",
        "/cancel" to "❌ Cancel",
    )

    fun fromTextFallback(text: String): TelegramInlineKeyboard? {
        val line = text.lines().map { it.trim() }.firstOrNull { fallbackLine.containsMatchIn(it) }
            ?: text.takeIf { it.contains("Text fallback", ignoreCase = true) }
            ?: return null
        val commands = slashToken.findAll(line).map { it.value.lowercase() }.distinct().toList()
        if (commands.isEmpty()) return null
        val approve = commands.filter { it != "/cancel" }
        val cancel = commands.filter { it == "/cancel" }
        val rows = buildList {
            if (approve.isNotEmpty()) {
                add(approve.map { cmd ->
                    TelegramInlineButton(
                        text = knownLabels[cmd] ?: cmd,
                        action = TelegramInlineAction.SendText(cmd),
                    )
                })
            }
            if (cancel.isNotEmpty()) {
                add(cancel.map { cmd ->
                    TelegramInlineButton(
                        text = knownLabels[cmd] ?: cmd,
                        action = TelegramInlineAction.SendText(cmd),
                    )
                })
            }
        }
        if (rows.isEmpty()) return null
        return TelegramInlineKeyboard(chatId = 0L, messageId = 0L, rows = rows)
    }

    fun stripFallbackLine(text: String): String {
        val lines = text.lines().toMutableList()
        lines.removeAll { line ->
            val t = line.trim()
            t.contains("Text fallback", ignoreCase = true) ||
                (t.startsWith("_") && t.contains("/approve", ignoreCase = true))
        }
        return lines.joinToString("\n").trimEnd()
    }
}
