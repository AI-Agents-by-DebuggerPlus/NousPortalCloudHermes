package com.nous.ahcc.data.local

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Append-only log of Hermes tool and shell lines. Not shown in the chat transcript. */
class ChatToolLog(context: Context) {
    private val file = File(context.filesDir, "chat-logs/service.log")
    private val lock = Any()

    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val block = lines.joinToString(separator = "") { "$stamp  $it\n" }
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(block)
        }
    }

    fun read(): String = synchronized(lock) {
        if (!file.exists()) "" else file.readText()
    }

    fun clear() {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.writeText("")
        }
    }
}
