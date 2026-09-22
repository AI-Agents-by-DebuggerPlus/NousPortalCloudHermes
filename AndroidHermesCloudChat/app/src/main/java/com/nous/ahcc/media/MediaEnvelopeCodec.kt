package com.nous.ahcc.media

import android.util.Base64
import com.nous.ahcc.domain.model.ChatAttachment
import com.nous.ahcc.domain.model.InboundMedia
import com.nous.ahcc.domain.model.MediaAttachmentWire
import com.nous.ahcc.domain.model.MediaEnvelope
import com.nous.ahcc.domain.model.MediaKind
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

object MediaEnvelopeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val blockPattern = Pattern.compile(
        Regex.escape(MediaEnvelope.BEGIN) + "\\s*([\\s\\S]*?)\\s*" + Regex.escape(MediaEnvelope.END)
    )
    private val legacyVoicePattern = Pattern.compile(
        "\\[AHCC_VOICE\\s+mime=\"([^\"]+)\"\\s+name=\"([^\"]+)\"\\]\\s*([A-Za-z0-9+/=\\s]+)\\s*\\[/AHCC_VOICE\\]",
        Pattern.CASE_INSENSITIVE
    )

    fun wiresOf(attachments: List<ChatAttachment>): List<MediaAttachmentWire> =
        attachments.map { it.toWire() }

    fun buildOutboundText(
        caption: String?,
        attachments: List<ChatAttachment>,
        sessionId: String?
    ): String {
        val wires = attachments.map { it.toWire() }
        val envelope = MediaEnvelope(
            direction = MediaEnvelope.DIR_CLIENT_TO_AGENT,
            session_id = sessionId,
            caption = caption?.takeIf { it.isNotBlank() },
            attachments = wires
        )
        val body = json.encodeToString(MediaEnvelope.serializer(), envelope)
        val captionLine = caption?.takeIf { it.isNotBlank() } ?: defaultCaption(attachments)
        return buildString {
            append(captionLine)
            append("\n\n")
            append(MediaEnvelope.BEGIN)
            append('\n')
            append(body)
            append('\n')
            append(MediaEnvelope.END)
        }
    }

    fun parseInbound(text: String, cacheDir: File): List<InboundMedia> {
        val result = mutableListOf<InboundMedia>()
        val matcher = blockPattern.matcher(text)
        while (matcher.find()) {
            val raw = matcher.group(1)?.trim().orEmpty()
            if (raw.isBlank()) continue
            runCatching {
                val env = json.decodeFromString(MediaEnvelope.serializer(), raw)
                env.attachments.forEach { wire ->
                    val kind = MediaKind.fromWire(wire.kind) ?: return@forEach
                    val bytes = Base64.decode(wire.data, Base64.DEFAULT)
                    val dir = File(cacheDir, "ahcc_media").also { it.mkdirs() }
                    val out = File(dir, sanitize(wire.name.ifBlank { "${wire.id}.bin" }))
                    out.writeBytes(bytes)
                    result += InboundMedia(
                        id = wire.id.ifBlank { UUID.randomUUID().toString() },
                        kind = kind,
                        mime = wire.mime,
                        name = wire.name,
                        filePath = out.absolutePath,
                        durationMs = wire.duration_ms
                    )
                }
            }
        }
        val legacy = legacyVoicePattern.matcher(text)
        while (legacy.find()) {
            val mime = legacy.group(1) ?: "audio/mpeg"
            val name = legacy.group(2) ?: "voice.mp3"
            val b64 = legacy.group(3)?.replace("\\s".toRegex(), "").orEmpty()
            if (b64.isBlank()) continue
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val dir = File(cacheDir, "ahcc_media").also { it.mkdirs() }
                val out = File(dir, sanitize(name))
                out.writeBytes(bytes)
                result += InboundMedia(
                    id = UUID.randomUUID().toString(),
                    kind = MediaKind.Voice,
                    mime = mime,
                    name = name,
                    filePath = out.absolutePath
                )
            }
        }
        return result
    }

    fun stripMediaBlocksForDisplay(text: String): String {
        var t = blockPattern.matcher(text).replaceAll("[media attachment]")
        t = legacyVoicePattern.matcher(t).replaceAll("[voice]")
        return t.trim()
    }

    private fun ChatAttachment.toWire(): MediaAttachmentWire {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return MediaAttachmentWire(
            id = id,
            kind = kind.wire(),
            mime = mime,
            name = name,
            size = bytes.size.toLong(),
            duration_ms = durationMs,
            encoding = "base64",
            data = b64,
            sha256 = sha256Hex(bytes)
        )
    }

    private fun defaultCaption(attachments: List<ChatAttachment>): String {
        val kinds = attachments.map { it.kind }.toSet()
        return when {
            kinds == setOf(MediaKind.Voice) -> "Голосовое сообщение"
            kinds == setOf(MediaKind.Photo) -> "Фото"
            kinds.singleOrNull() == MediaKind.File -> "Файл: ${attachments.first().name}"
            else -> "Вложения (${attachments.size})"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
