package com.nous.ahcc.domain.model

import org.json.JSONObject

/**
 * Hermes mixes shell cards and the spoken answer in one Telegram text.
 * The chat and TTS use [answer]. Tool lines go to the status line and the log.
 */
object AgentReplyParts {
    data class Split(val answer: String, val tools: List<String>)

    fun split(raw: String): Split {
        val answer = mutableListOf<String>()
        val tools = mutableListOf<String>()
        raw.lines().forEach { line ->
            val text = line.trim()
            if (text.isEmpty() || isOwnEcho(text)) return@forEach
            if (isToolLine(text)) tools += text else answer += text
        }
        return Split(answer.joinToString("\n"), tools)
    }

    /**
     * Text to speak. Service cards stay in the chat bubble and are left out:
     * an emoji plus a short status ("terminal", "Self-improvement review: Memory updated").
     * A real sentence after an emoji is spoken without the emoji.
     */
    fun forSpeech(answer: String): String =
        answer.lines()
            .map { it.trim() }
            .mapNotNull { lineForSpeech(it) }
            .joinToString("\n")

    fun lineForSpeech(line: String): String? {
        val text = line.trim()
        if (text.isEmpty() || isOwnEcho(text) || isToolLine(text) || isSpeechHeading(text)) return null
        if (isFlashcardPayload(text) || requestsLocalTranscription(text)) return null
        val span = leadingEmojiSpan(text)
        val body = if (span > 0) text.substring(span).trim() else text
        if (body.isEmpty() || isStatusStamp(body)) return null
        if (span > 0 && !looksLikeProse(body)) return null
        return body
    }

    /**
     * One run per line. A Russian line is spoken entirely in Russian, including
     * an English word inside it. A line with no Cyrillic is spoken in English.
     */
    fun speechRuns(text: String): List<SpeechRun> {
        val runs = mutableListOf<SpeechRun>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val lang = if (line.any(::isCyrillic)) "ru" else "en"
            val last = runs.lastOrNull()
            if (last != null && last.language == lang) {
                runs[runs.lastIndex] = last.copy(text = "${last.text}\n$line")
            } else {
                runs += SpeechRun(lang, line)
            }
        }
        return runs
    }

    data class SpeechRun(val language: String, val text: String)

    data class Flashcard(val en: String, val ru: String)

    /** Last real en/ru card in the reply. Instruction samples are ignored. */
    fun lastFlashcard(text: String): Flashcard? {
        var found: Flashcard? = null
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until text.length) {
                if (text[j] == '{') depth++
                else if (text[j] == '}') {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
            if (end < 0) break
            val slice = text.substring(start, end + 1)
            parseFlashcard(slice)?.let { found = it }
            i = end + 1
        }
        return found
    }

    private fun parseFlashcard(json: String): Flashcard? {
        return try {
            val root = JSONObject(json)
            if (!root.optString("type").equals("flashcard", ignoreCase = true)) return null
            val en = root.optString("en").trim()
            val ru = root.optString("ru").trim()
            if (en.isEmpty() || ru.isEmpty() || isTemplateCard(en, ru)) null
            else Flashcard(en, ru)
        } catch (_: Exception) {
            null
        }
    }

    private fun isTemplateCard(en: String, ru: String): Boolean {
        val e = en.lowercase()
        val r = ru.lowercase()
        return e.contains("короткая английская") ||
            r.contains("живой русский") ||
            en.contains('<') ||
            ru.contains('<')
    }

    /** Agent could not transcribe the voice note and asks the phone to take over. */
    fun requestsLocalTranscription(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until text.length) {
                if (text[j] == '{') depth++
                else if (text[j] == '}') {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
            if (end < 0) break
            if (isLocalTranscriptionCommand(text.substring(start, end + 1))) return true
            i = end + 1
        }
        return false
    }

    private fun isLocalTranscriptionCommand(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            root.optString("skill").equals("local_transcription", ignoreCase = true) &&
                root.optBoolean("enabled", false)
        } catch (_: Exception) {
            false
        }
    }

    fun isFlashcardRelay(text: String): Boolean = isFlashcardPayload(text)

    private fun isFlashcardPayload(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("\"type\"") && lower.contains("flashcard")) ||
            (lower.contains("\"skill\"") && lower.contains("flashcard_"))
    }

    private fun isCyrillic(ch: Char): Boolean {
        if (!ch.isLetter()) return false
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CYRILLIC ||
            block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY ||
            block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A ||
            block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B
    }

    fun isSpeechHeading(line: String): Boolean {
        val text = line.trim()
        if (text.equals("PC terminal", ignoreCase = true) ||
            text.equals("terminal", ignoreCase = true) ||
            text.equals("shell", ignoreCase = true) ||
            text.equals("PC", ignoreCase = true) ||
            isToolLine(text)
        ) {
            return true
        }
        val words = text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return words.isEmpty() ||
            words.equals("terminal", ignoreCase = true) ||
            words.equals("pc terminal", ignoreCase = true) ||
            words.equals("computer terminal", ignoreCase = true) ||
            words.equals("pc", ignoreCase = true)
    }

    fun containsAnswer(raw: String): Boolean = forSpeech(split(raw).answer).isNotBlank()

    /** Hermes status card: "Self-improvement review: Memory updated" and similar stamps. */
    private fun isStatusStamp(text: String): Boolean = statusStamp.containsMatchIn(text)

    /**
     * A spoken sentence, as opposed to a short emoji status label.
     * Quotes, sentence punctuation, or a long run of words count as prose.
     */
    private fun looksLikeProse(text: String): Boolean {
        if (quotedSentence.containsMatchIn(text)) return true
        if (text.any { it == '.' || it == '!' || it == '?' || it == '…' }) return true
        val words = text.split(Regex("\\s+")).count { word -> word.any(Char::isLetter) }
        return words >= 8
    }

    /** Index just after a leading emoji (and its joiners). Zero when the line does not start with one. */
    private fun leadingEmojiSpan(text: String): Int {
        var i = 0
        var sawEmoji = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                sawEmoji = true
                i += count
                continue
            }
            if (sawEmoji && text[i].isWhitespace()) {
                i += count
                continue
            }
            break
        }
        return if (sawEmoji) i else 0
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        if (cp == 0xFE0F || cp == 0x200D || cp in 0x1F3FB..0x1F3FF) return true
        if (cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF) return true
        return Character.getType(cp) == Character.OTHER_SYMBOL.toInt()
    }

    fun isOwnEcho(text: String): Boolean =
        text.matches(Regex("""Голосовое сообщение \(\d+ ms\)"""))

    fun isToolLine(line: String): Boolean =
        line.equals("shell", ignoreCase = true) ||
            line.equals("terminal", ignoreCase = true) ||
            line.startsWith("tool_") ||
            line.startsWith("cd ") ||
            line.startsWith("which ") ||
            line.startsWith("grep ") ||
            line.startsWith("find ") ||
            line.startsWith("env ") ||
            line.startsWith("cat ") ||
            line.startsWith("pip ") ||
            line.contains("python3") ||
            line.contains("/opt/") ||
            line.startsWith("Reading skill", ignoreCase = true)

    private val statusStamp = Regex(
        """(?i)self-improvement|memory updated|reading skill|skill loaded|memory saved|interrupting current task|i'll respond to your message shortly"""
    )
    private val quotedSentence = Regex("""["«„][^"»“]{8,}["»“]""")
}
