package com.bttest.v1.headset

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

    fun isBtPlayLabel(label: String): Boolean {
        val n = normalize(label)
        return n == "MEDIA_PLAY" ||
            n == "MEDIA_PLAY_PAUSE" ||
            n == "HEADSETHOOK" ||
            n == "PLAY"
    }

    /** Play-жест: Play/Pause/Hook — подряд часто чередуются PLAY и PAUSE. */
    fun isBtPlayGestureLabel(label: String): Boolean {
        val n = normalize(label)
        return isBtPlayLabel(n) || n == "MEDIA_PAUSE" || n == "PAUSE"
    }

    fun displayLabel(label: String): String = when (normalize(label)) {
        "MEDIA_PLAY", "PLAY" -> "Play"
        "MEDIA_PAUSE", "PAUSE" -> "Pause"
        "MEDIA_PLAY_PAUSE" -> "Play/Pause"
        "MEDIA_NEXT", "NEXT" -> "Next"
        "MEDIA_PREVIOUS", "PREVIOUS" -> "Previous"
        "MEDIA_STOP", "STOP" -> "Stop"
        "HEADSETHOOK" -> "HeadsetHook"
        else -> label
    }

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

