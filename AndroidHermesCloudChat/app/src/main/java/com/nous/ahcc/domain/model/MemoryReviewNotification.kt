package com.nous.ahcc.domain.model

/**
 * Hermes background self-improvement / memory write-approval notices in Telegram.
 */
data class MemoryPendingItem(
    val id: String,
    val detail: String,
)

object MemoryReviewNotification {
    /** Hermes staged write ids (hex), e.g. 92559e0e — not words like "Pending". */
    private val pendingIdToken = Regex("""^[a-f0-9]{8}$""", RegexOption.IGNORE_CASE)
    private val pendingIdLine =
        Regex("""(?m)^\s+([a-f0-9]{8})\s*(?:\[auto\])?\s""", RegexOption.IGNORE_CASE)
    private val pendingItemStart =
        Regex("""(?m)^\s*([a-f0-9]{8})\s*(?:\[auto\])?\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun isNotification(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val lower = t.lowercase()
        if (lower.contains("self-improvement review")) return true
        if (lower.contains("/memory pending") && lower.contains("staged")) return true
        if (lower.contains("staged for your approval") &&
            (lower.contains("memory") || lower.contains("profile updated"))
        ) {
            return true
        }
        if (lower.contains("pending memory writes")) return true
        return false
    }

    fun isValidPendingId(id: String): Boolean = pendingIdToken.matches(id.trim())

    /** Parse ids from a `/memory pending` bot reply. */
    fun parsePendingIds(pendingReply: String): List<String> =
        pendingIdLine.findAll(pendingReply).map { it.groupValues[1] }.distinct().toList()

    /** Parse staged items from a pending list or verbose review notice. */
    fun parsePendingItems(text: String): List<MemoryPendingItem> {
        val matches = pendingItemStart.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val id = match.groupValues[1]
            val inline = match.groupValues[2].trim()
            val blockStart = match.range.last + 1
            val blockEnd =
                if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val block = text.substring(blockStart, blockEnd).trim()
            val detail = (inline + if (block.isNotEmpty()) "\n$block" else "").trim()
            MemoryPendingItem(id = id, detail = detail.ifBlank { id })
        }
    }
}
