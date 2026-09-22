package com.nous.ahcc.desktop.media

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

object DesktopMediaEnvelope {
    const val BEGIN = "<<<AHCC_MEDIA_V1"
    const val END = ">>>"
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    data class Attachment(
        val id: String = UUID.randomUUID().toString(),
        val kind: String,
        val mime: String,
        val name: String,
        val bytes: ByteArray,
    )

    fun fromFile(file: File): Attachment {
        val bytes = file.readBytes()
        if (bytes.size > MAX_FILE_BYTES) {
            error("File too large (max ${MAX_FILE_BYTES / 1024} KiB)")
        }
        val mime = guessMime(file.name)
        val kind = when {
            mime.startsWith("image/") -> "photo"
            mime.startsWith("audio/") -> "voice"
            else -> "file"
        }
        return Attachment(kind = kind, mime = mime, name = file.name, bytes = bytes)
    }

    fun buildText(caption: String?, attachment: Attachment, sessionId: String): String {
        val b64 = Base64.getEncoder().encodeToString(attachment.bytes)
        val sha = sha256Hex(attachment.bytes)
        val captionLine = caption?.takeIf { it.isNotBlank() }
            ?: when (attachment.kind) {
                "photo" -> "Photo"
                "voice" -> "Voice note"
                else -> "File: ${attachment.name}"
            }
        // Compact JSON (no kotlinx dependency for this tiny payload)
        val json = buildString {
            append('{')
            append("\"schema\":\"ahcc.media.v1\",")
            append("\"direction\":\"client_to_agent\",")
            append("\"session_id\":").append(jsonString(sessionId)).append(',')
            append("\"caption\":").append(jsonString(captionLine)).append(',')
            append("\"attachments\":[{")
            append("\"id\":").append(jsonString(attachment.id)).append(',')
            append("\"kind\":").append(jsonString(attachment.kind)).append(',')
            append("\"mime\":").append(jsonString(attachment.mime)).append(',')
            append("\"name\":").append(jsonString(attachment.name)).append(',')
            append("\"size\":").append(attachment.bytes.size).append(',')
            append("\"encoding\":\"base64\",")
            append("\"data\":").append(jsonString(b64)).append(',')
            append("\"sha256\":").append(jsonString(sha))
            append("}]}")
        }
        return "$captionLine\n\n$BEGIN\n$json\n$END"
    }

    fun placeholder(attachment: Attachment, caption: String?): String = buildString {
        if (!caption.isNullOrBlank()) append(caption).append('\n')
        append('[').append(attachment.kind).append("] ").append(attachment.name)
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun jsonString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
}
