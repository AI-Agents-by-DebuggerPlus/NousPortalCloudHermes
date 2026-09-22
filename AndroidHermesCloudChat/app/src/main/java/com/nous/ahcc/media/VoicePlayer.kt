package com.nous.ahcc.media

import android.media.MediaPlayer
import android.util.Log
import java.io.File

class VoicePlayer {
    private var player: MediaPlayer? = null
    private var playingPath: String? = null

    val isPlaying: Boolean get() = player?.isPlaying == true
    val currentPath: String? get() = playingPath

    fun play(path: String, onComplete: (() -> Unit)? = null) {
        stop()
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "missing $path")
            return
        }
        player = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener {
                stop()
                onComplete?.invoke()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "play error what=$what extra=$extra")
                stop()
                true
            }
            prepare()
            start()
        }
        playingPath = path
        Log.i(TAG, "play $path")
    }

    fun toggle(path: String, onComplete: (() -> Unit)? = null) {
        if (isPlaying && playingPath == path) {
            stop()
        } else {
            play(path, onComplete)
        }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        playingPath = null
    }

    companion object {
        private const val TAG = "AHCC-VoicePlay"
    }
}
