package com.nous.ahcc.domain.model

/**
 * Hermes gateway can append a runtime footer (`model · 68% · ~/…`) when
 * `/footer on` or `display.runtime_footer.enabled` is set. `/context` replies
 * also expose a percent. AHCC uses that to warn before the window fills.
 */
enum class ContextPressure {
    None,
    /** ≥50% — auto-compress threshold territory. */
    Watch,
    /** ≥70% — suggest /compress or new session. */
    High,
    /** ≥85% — gateway hygiene / overflow risk. */
    Critical,
}

data class ContextPressureNotice(
    val percent: Int,
    val level: ContextPressure,
    val message: String,
)

object ContextUsageParser {
    private val footerLine = Regex(
        """(?i)^[\w./:@+-]+(?:\s*[·|]\s*[\w./:@%~+-]+)+\s*$"""
    )
    private val percentToken = Regex("""(?<![\d.])(\d{1,3})\s*%""")
    private val contextUsageParen = Regex(
        """(?i)context[^%\n]{0,40}\((\d{1,3})\s*%\)"""
    )
    private val usageGauge = Regex(
        """(?i)(?:usage|occupancy|window|filled|context)\s*[:=]?\s*(\d{1,3})\s*%"""
    )
    private val compressionHints = Regex(
        """(?i)(context\s+compress|compressed\s+context|session\s+hygiene|""" +
            """context\s+(?:window\s+)?(?:full|overflow|too\s+large)|""" +
            """prompt\s+(?:is\s+)?too\s+long)"""
    )

    fun pressure(percent: Int): ContextPressure = when {
        percent >= 85 -> ContextPressure.Critical
        percent >= 70 -> ContextPressure.High
        percent >= 50 -> ContextPressure.Watch
        else -> ContextPressure.None
    }

    fun noticeFor(percent: Int): ContextPressureNotice? {
        val level = pressure(percent)
        if (level == ContextPressure.None) return null
        val message = when (level) {
            ContextPressure.Watch ->
                "Контекст ~$percent%. Скоро авто-сжатие. При новой задаче — «новая сессия»."
            ContextPressure.High ->
                "Контекст высокий ($percent%). Рекомендуется /compress или «новая сессия»."
            ContextPressure.Critical ->
                "Критический уровень контекста ($percent%). Срочно «новая сессия» или /compress — иначе возможны сбои."
            ContextPressure.None -> return null
        }
        return ContextPressureNotice(percent, level, message)
    }

    /** Best-effort percent from a Hermes reply or `/context` dump. */
    fun parsePercent(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        contextUsageParen.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }
            ?.let { return it }

        usageGauge.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 0..100 }
            ?.let { return it }

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines.asReversed()) {
            if (!looksLikeFooterOrGauge(line)) continue
            percentToken.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 0..100 }
                ?.let { return it }
        }

        val tail = lines.takeLast(3).joinToString("\n")
        if (lines.size >= 2 && looksLikeFooterOrGauge(lines.last())) {
            percentToken.find(tail)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 0..100 }
                ?.let { return it }
        }
        return null
    }

    fun mentionsCompression(text: String): Boolean =
        compressionHints.containsMatchIn(text)

    fun isRuntimeFooterLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (!percentToken.containsMatchIn(t)) return false
        if (t.contains('·') || t.contains('|')) return true
        return footerLine.matches(t) && t.length <= 120
    }

    fun stripRuntimeFooter(text: String): String {
        val lines = text.lines().toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        if (lines.isNotEmpty() && isRuntimeFooterLine(lines.last())) {
            lines.removeAt(lines.lastIndex)
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        }
        return lines.joinToString("\n").trimEnd()
    }

    private fun looksLikeFooterOrGauge(line: String): Boolean {
        if (isRuntimeFooterLine(line)) return true
        val lower = line.lowercase()
        return lower.contains("context") ||
            lower.contains("usage") ||
            lower.contains("occupancy") ||
            lower.contains("window") ||
            line.contains('·')
    }
}
