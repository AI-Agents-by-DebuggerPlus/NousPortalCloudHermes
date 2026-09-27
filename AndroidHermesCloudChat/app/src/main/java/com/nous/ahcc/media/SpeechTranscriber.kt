package com.nous.ahcc.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Live transcription via the system Google SpeechRecognizer, including Bluetooth SCO
 * when a headset is connected. Same path as AndroidChat's voice test.
 */
class SpeechTranscriber(context: Context) {
    private val app = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var pending: ((SpeechTranscript) -> Unit)? = null
    private var scoEnabled = false
    var listening: Boolean = false
        private set

    fun start(localeTag: String, onFinished: (SpeechTranscript) -> Unit) {
        mainHandler.post {
            if (listening) {
                finish(SpeechTranscript.Cancelled)
            }
            pending = onFinished
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                finish(SpeechTranscript.Error("Нет разрешения RECORD_AUDIO"))
                return@post
            }
            if (!SpeechRecognizer.isRecognitionAvailable(app)) {
                finish(SpeechTranscript.Error("SpeechRecognizer недоступен на устройстве"))
                return@post
            }
            begin(localeTag)
        }
    }

    fun cancel() {
        mainHandler.post {
            if (!listening) return@post
            finish(SpeechTranscript.Cancelled)
        }
    }

    fun shutdown() {
        mainHandler.post {
            pending = null
            listening = false
            destroyRecognizer()
            disableSco()
        }
    }

    private fun begin(localeTag: String) {
        destroyRecognizer()
        enableSco()
        val sr = SpeechRecognizer.createSpeechRecognizer(app)
        recognizer = sr
        listening = true
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                finish(SpeechTranscript.Error(errorLabel(error), error))
            }

            override fun onResults(results: Bundle?) {
                val best = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (best.isEmpty()) finish(SpeechTranscript.Empty) else finish(SpeechTranscript.Success(best))
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        try {
            sr.startListening(intent)
            Log.i(TAG, "startListening locale=$localeTag")
        } catch (ex: Exception) {
            finish(SpeechTranscript.Error(ex.message ?: "startListening failed"))
        }
    }

    private fun finish(result: SpeechTranscript) {
        val callback = pending
        pending = null
        listening = false
        destroyRecognizer()
        disableSco()
        if (callback != null) mainHandler.post { callback(result) }
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun enableSco() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoEnabled = true
            }
        }
    }

    private fun disableSco() {
        if (!scoEnabled) return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            am.mode = AudioManager.MODE_NORMAL
            scoEnabled = false
        }
    }

    companion object {
        private const val TAG = "AHCC-Stt"

        fun errorLabel(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_$code"
        }
    }
}

sealed class SpeechTranscript {
    data class Success(val text: String) : SpeechTranscript()
    data object Empty : SpeechTranscript()
    data object Cancelled : SpeechTranscript()
    data class Error(val message: String, val errorCode: Int = -1) : SpeechTranscript()
}
