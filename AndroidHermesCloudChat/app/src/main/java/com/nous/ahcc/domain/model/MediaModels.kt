package com.nous.ahcc.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaEnvelope(
    val schema: String = SCHEMA,
    val direction: String,
    val session_id: String? = null,
    val caption: String? = null,
    val attachments: List<MediaAttachmentWire> = emptyList()
) {
    companion object {
        const val SCHEMA = "ahcc.media.v1"
        const val DIR_CLIENT_TO_AGENT = "client_to_agent"
        const val DIR_AGENT_TO_CLIENT = "agent_to_client"
        const val BEGIN = "<<<AHCC_MEDIA_V1"
        const val END = ">>>"
    }
}

@Serializable
data class MediaAttachmentWire(
    val id: String,
    val kind: String,
    val mime: String,
    val name: String,
    val size: Long,
    val duration_ms: Long? = null,
    val encoding: String = "base64",
    val data: String,
    val sha256: String? = null
)

enum class MediaKind {
    Voice,
    Photo,
    File;

    fun wire(): String = when (this) {
        Voice -> "voice"
        Photo -> "photo"
        File -> "file"
    }

    companion object {
        fun fromWire(value: String?): MediaKind? = when (value?.lowercase()) {
            "voice" -> Voice
            "photo" -> Photo
            "file" -> File
            else -> null
        }
    }
}

/** Local attachment held in UI / before send. */
data class ChatAttachment(
    val id: String,
    val kind: MediaKind,
    val mime: String,
    val name: String,
    val bytes: ByteArray,
    val durationMs: Long? = null,
    val localPath: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatAttachment) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/** Parsed inbound media ready for playback / open. */
data class InboundMedia(
    val id: String,
    val kind: MediaKind,
    val mime: String,
    val name: String,
    val filePath: String,
    val durationMs: Long? = null
)
