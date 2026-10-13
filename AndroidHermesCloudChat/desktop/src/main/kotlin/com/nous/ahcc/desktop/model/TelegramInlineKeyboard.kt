package com.nous.ahcc.desktop.model

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
    data class Callback(val data: ByteArray) : TelegramInlineAction() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Callback) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Url(val url: String) : TelegramInlineAction()
    data class SendText(val text: String) : TelegramInlineAction()
}

object TelegramInlineKeyboardParser {
    private val fallbackLine = Regex(
        """(?i)_?\s*text\s+fallback\s*:\s*reply\s+(/[\w]+)(?:\s*,\s*(/[\w]+))*\s*(?:or\s+(/[\w]+))?\s*\.?_?"""
    )
    private val slashToken = Regex("""/[\w]+""")

    private val knownLabels = mapOf(
        "/approve" to "✅ Allow Once",
        "/approve_once" to "✅ Allow Once",
        "/once" to "✅ Allow Once",
        "/session" to "✅ Session",
        "/always" to "🔒 Always",
        "/deny" to "❌ Deny",
        "/cancel" to "❌ Deny",
        "/reject" to "❌ Deny",
    )

    fun fromTextFallback(text: String): TelegramInlineKeyboard? {
        val line = text.lines().map { it.trim() }.firstOrNull { fallbackLine.containsMatchIn(it) }
            ?: text.takeIf { it.contains("Text fallback", ignoreCase = true) }
        if (line != null) {
            val commands = slashToken.findAll(line).map { it.value.lowercase() }.distinct().toList()
            if (commands.isNotEmpty()) {
                return keyboardFromCommands(commands)
            }
        }
        return fromApprovalHeuristic(text)
    }

    /** When live markup is missing but Hermes approval prose is present. */
    fun fromApprovalHeuristic(text: String): TelegramInlineKeyboard? {
        val lower = text.lowercase()
        val isApproval = lower.contains("needs your ok") ||
            lower.contains("wants to run") ||
            lower.contains("approval") && lower.contains("flagged")
        if (!isApproval) return null
        return TelegramInlineKeyboard(
            chatId = 0L,
            messageId = 0L,
            rows = listOf(
                listOf(
                    TelegramInlineButton("✅ Allow Once", TelegramInlineAction.SendText("/approve")),
                    TelegramInlineButton("✅ Session", TelegramInlineAction.SendText("/session")),
                ),
                listOf(
                    TelegramInlineButton("❌ Deny", TelegramInlineAction.SendText("/deny")),
                ),
            ),
        )
    }

    private fun keyboardFromCommands(commands: List<String>): TelegramInlineKeyboard {
        val deny = commands.filter { it in setOf("/cancel", "/deny", "/reject") }
        val allow = commands.filter { it !in setOf("/cancel", "/deny", "/reject") }
        val rows = buildList {
            if (allow.isNotEmpty()) {
                add(allow.map { cmd ->
                    TelegramInlineButton(knownLabels[cmd] ?: cmd, TelegramInlineAction.SendText(cmd))
                })
            }
            if (deny.isNotEmpty()) {
                add(deny.map { cmd ->
                    TelegramInlineButton(knownLabels[cmd] ?: cmd, TelegramInlineAction.SendText(cmd))
                })
            }
        }
        return TelegramInlineKeyboard(0L, 0L, rows)
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
