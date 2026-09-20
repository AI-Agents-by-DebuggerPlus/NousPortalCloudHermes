package com.nous.ahcc.headset

import android.view.KeyEvent

object HeadsetButtonNames {
    fun fromKeyCode(code: Int): String? = when (code) {
        KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "MEDIA_PAUSE"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
        KeyEvent.KEYCODE_MEDIA_STOP -> "MEDIA_STOP"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MEDIA_FAST_FORWARD"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "MEDIA_REWIND"
        KeyEvent.KEYCODE_VOICE_ASSIST -> "VOICE_ASSIST"
        else -> null
    }

    fun normalize(label: String): String =
        label.trim().uppercase().ifEmpty { "UNKNOWN" }

    val simulateLabels: List<String> = listOf(
        "MEDIA_PLAY",
        "MEDIA_PAUSE",
        "MEDIA_PLAY_PAUSE",
        "MEDIA_NEXT",
        "MEDIA_PREVIOUS",
        "MEDIA_STOP",
        "HEADSETHOOK"
    )
}
