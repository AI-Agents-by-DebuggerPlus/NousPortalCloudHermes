package com.nous.ahcc.domain.model

/**
 * Short Russian summaries for Hermes memory / self-improvement notices (source text is usually English).
 */
object MemoryReviewSummarizer {

    fun summarizeBody(text: String): String {
        val items = MemoryReviewNotification.parsePendingItems(text)
        if (items.isNotEmpty()) {
            val header = pendingHeaderRu(text)
            val lines = items.map { "• ${summarizeItem(it)}" }
            return buildString {
                append(header)
                append('\n')
                append(lines.joinToString("\n"))
            }.trim()
        }
        return summarizeFreeform(text)
    }

    fun summarizeItem(item: MemoryPendingItem): String {
        val op = operationRu(item.detail)
        val topic = topicRu(item.detail)
        return "${item.id}: $op — $topic"
    }

    fun summarizeMemoryReply(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (!looksLikeMemoryOutcome(t)) return null
        return summarizeFreeform(t)
    }

    private fun pendingHeaderRu(text: String): String {
        val countMatch = Regex("""(?i)pending memory writes\s*\((\d+)\)""").find(text)
        if (countMatch != null) {
            val n = countMatch.groupValues[1]
            return "Ожидают подтверждения: $n изменений в памяти Hermes"
        }
        if (text.contains("self-improvement", ignoreCase = true)) {
            return "Self-improvement: нужно подтвердить изменение памяти"
        }
        return "Уведомление о памяти Hermes"
    }

    private fun operationRu(detail: String): String {
        val d = detail.lowercase()
        return when {
            d.contains("entry changed since it was staged") ->
                "не применено (запись уже изменилась)"
            d.contains("no operations were applied") ->
                "пакет не применён (all-or-nothing)"
            d.contains("replaces entry") || d.contains("replace entry") || d.contains("replace on memory") ->
                "заменить запись"
            d.contains("removes entry") || d.contains("remove:") || d.contains("remove on memory") ->
                "удалить запись"
            d.contains("add:") || d.contains("adds entry") ->
                "добавить запись"
            d.contains("background review consolidation") ->
                "фоновая консолидация памяти"
            else -> "изменение памяти"
        }
    }

    private fun topicRu(detail: String): String {
        val lower = detail.lowercase()
        val themed = when {
            lower.contains("faster_whisper") || lower.contains("speech-to-text") ||
                lower.contains("transcrib") || lower.contains("voice messages") ->
                "транскрипция голосовых сообщений (faster_whisper)"
            lower.contains("tony stark") || lower.contains("telegram") && lower.contains("voice") ->
                "голосовые сообщения пользователя в Telegram"
            lower.contains("расшифров") ->
                "правила формулировок про расшифровку в ответах"
            lower.contains("flashcard") || lower.contains("english_tutor") ->
                "настройки English Tutor / карточки"
            lower.contains("skill") ->
                "навык (skill) агента"
            else -> null
        }
        if (themed != null) return themed

        val quoted = Regex("""matching '([^']{8,120})'""").find(detail)?.groupValues?.get(1)
        if (quoted != null) return clipRu(quoted)

        val firstLine = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val cleaned = firstLine
            .removePrefix("background review consolidation (batch on memory):")
            .removePrefix("background review consolidation (replace on memory):")
            .replace(Regex("""^\s*-\s*(remove|replace|add):\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
        return clipRu(cleaned.ifBlank { detail })
    }

    private fun summarizeFreeform(text: String): String {
        val lower = text.lowercase()
        if (lower.contains("entry changed since it was staged")) {
            val id = Regex("""^([A-Za-z0-9_-]{6,}):""").find(text.trim())?.groupValues?.get(1)
            val prefix = id?.let { "$it: " }.orEmpty()
            return prefix + "Не применено: запись в памяти изменилась после постановки в очередь. " +
                "Нужно заново сформировать изменение или отклонить (reject)."
        }
        if (lower.contains("no pending memory")) {
            return "Нет ожидающих изменений памяти."
        }
        if (lower.contains("pending memory writes")) {
            return summarizeBody(text)
        }
        val idLine = Regex("""^([A-Za-z0-9_-]{6,})\s*(?:\[auto\])?\s*(.*)$""", RegexOption.MULTILINE)
            .find(text)
        if (idLine != null) {
            val item = MemoryPendingItem(idLine.groupValues[1], idLine.groupValues[2].trim())
            return summarizeItem(item)
        }
        return clipRu(text, maxLen = 280)
    }

    private fun looksLikeMemoryOutcome(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("memory") ||
            lower.contains("staged") ||
            lower.contains("removes entry") ||
            lower.contains("replaces entry") ||
            lower.contains("entry changed since") ||
            lower.contains("pending memory") ||
            Regex("""^[a-f0-9]{8}:""").containsMatchIn(text)
    }

    private fun clipRu(text: String, maxLen: Int = 160): String {
        val oneLine = text.replace(Regex("""\s+"""), " ").trim()
        if (oneLine.length <= maxLen) return oneLine
        return oneLine.take(maxLen - 1) + "…"
    }
}
