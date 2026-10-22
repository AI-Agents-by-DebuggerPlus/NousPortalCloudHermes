package com.nous.ahcc.desktop.log

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Append-only chat transcript under repo [Logs/ahcc-desktop/].
 * Override with system property `ahcc.logs.dir` (path to parent Logs folder).
 */
object DesktopChatLog {
    private val lock = ReentrantLock()

    /**
     * Optional live sink: receives the same header + body text that is written to
     * disk (header line, newline, message body). Used by the main window log pane.
     * Invoked on the writing thread; subscribers must dispatch to their own thread.
     */
    @Volatile
    var sink: ((String) -> Unit)? = null

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    private val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())
    private val utcTsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val logDir: File by lazy {
        val override = System.getProperty("ahcc.logs.dir")?.trim().orEmpty()
        val dir = if (override.isNotEmpty()) {
            File(override)
        } else {
            resolveRepoLogsDir()
        }
        File(dir, "ahcc-desktop").also {
            it.mkdirs()
            System.err.println("[AHCC-LOG] dir=${it.absolutePath}")
        }
    }

    fun out(
        sessionId: String,
        addresseeId: String,
        text: String,
        note: String = "",
    ) = append("OUT", "user", sessionId, addresseeId, text, note)

    fun inbound(
        sessionId: String,
        addresseeId: String,
        role: String,
        text: String,
        note: String = "",
    ) = append("IN", role, sessionId, addresseeId, text, note)

    /**
     * Raw Telegram inbound as received from TDLib: plain text + entity list +
     * our markdown projection (for comparison). Written to telegram-raw-*.log.
     */
    fun telegramRaw(
        chatId: Long,
        messageId: Long,
        contentKind: String,
        plainText: String,
        entitiesDump: String,
        markdownProjection: String,
        note: String = "",
    ) {
        val body = buildString {
            appendLine("chatId=$chatId messageId=$messageId kind=$contentKind")
            if (note.isNotBlank()) appendLine("note=$note")
            appendLine("utc=${utcTsFmt.format(Instant.now())}")
            appendLine("--- TEXT (plain, as TDLib .text) ---")
            appendLine(plainText)
            appendLine("--- ENTITIES ---")
            appendLine(entitiesDump.trimEnd())
            appendLine("--- MARKDOWN PROJECTION (pre/preCode → fences) ---")
            appendLine(markdownProjection)
        }
        appendToFile(
            fileNamePrefix = "telegram-raw",
            direction = "IN",
            role = "telegram",
            sessionId = "-",
            addresseeId = "-",
            body = body,
            note = "raw-format",
        )
    }

    fun system(sessionId: String, text: String) =
        append("SYS", "system", sessionId, "-", text, "")

    /** Per-agent log file — every line routed to that agent, window open or not. */
    private fun agentLogFile(addresseeId: String): File =
        File(logDir, "agent-${safeId(addresseeId)}.log")

    private fun safeId(addresseeId: String): String = addresseeId
        .map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
        .joinToString("")
        .ifBlank { "unknown" }

    /** Wipes one agent's own log (used by the window's Clear button). */
    fun clearAgent(addresseeId: String) {
        if (addresseeId.isBlank() || addresseeId == "-") return
        lock.withLock {
            runCatching { agentLogFile(addresseeId).delete() }
        }
    }

    private fun append(
        direction: String,
        role: String,
        sessionId: String,
        addresseeId: String,
        text: String,
        note: String,
    ) {
        val body = text.trimEnd()
        if (body.isEmpty() && note.isEmpty()) return
        appendToFile(
            fileNamePrefix = "chat",
            direction = direction,
            role = role,
            sessionId = sessionId,
            addresseeId = addresseeId,
            body = body,
            note = note,
        )
    }

    private fun appendToFile(
        fileNamePrefix: String,
        direction: String,
        role: String,
        sessionId: String,
        addresseeId: String,
        body: String,
        note: String,
    ) {
        val now = Instant.now()
        val file = File(logDir, "$fileNamePrefix-${dayFmt.format(now)}.log")
        val header = buildString {
            append(tsFmt.format(now))
            append(' ')
            append(direction)
            append(' ')
            append(role)
            append(" session=")
            append(sessionId.ifBlank { "-" })
            append(" to=")
            append(addresseeId.ifBlank { "-" })
            if (note.isNotBlank()) {
                append(" note=")
                append(note)
            }
        }
        val block = buildString {
            appendLine(header)
            appendLine("-----")
            appendLine(body.trimEnd())
            appendLine("=====")
            appendLine()
        }
        lock.withLock {
            runCatching {
                logDir.mkdirs()
                file.appendText(block, Charsets.UTF_8)
                // Also keep a per-agent file so each agent's traffic is readable on its own,
                // regardless of whether that agent's chat window is open.
                if (fileNamePrefix == "chat" && addresseeId.isNotBlank() && addresseeId != "-") {
                    agentLogFile(addresseeId).appendText(block, Charsets.UTF_8)
                }
            }.onFailure { e ->
                System.err.println("[AHCC-LOG] write failed path=${file.absolutePath}: ${e.message}")
            }
        }
        sink?.let { s -> runCatching { s(header + "\n" + body.trimEnd()) } }
    }

    /** Prefer `<repo>/Logs`; walk up from cwd looking for repo marker or existing Logs. */
    private fun resolveRepoLogsDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val cur = dir ?: return@repeat
            val logs = File(cur, "Logs")
            if (logs.isDirectory) return logs
            if (File(cur, "AndroidHermesCloudChat").isDirectory && File(cur, ".git").exists()) {
                return logs.also { it.mkdirs() }
            }
            if (File(cur, "AndroidHermesCloudChat").isDirectory) {
                return logs.also { it.mkdirs() }
            }
            dir = cur.parentFile
        }
        return File(System.getProperty("user.home"), ".ahcc-desktop/Logs").also { it.mkdirs() }
    }
}
