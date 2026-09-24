package com.nous.ahcc.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Speaks once when the activity is created. */
class StartupGreeting(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts init status=$status")
            return
        }
        tts.language = Locale.US
        tts.speak(PHRASE, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        private const val TAG = "AHCC-TTS"
        private const val PHRASE = "AHCC is running"
        private const val UTTERANCE_ID = "ahcc-start"
    }
}
