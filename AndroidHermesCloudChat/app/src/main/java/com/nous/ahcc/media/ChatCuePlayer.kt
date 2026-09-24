package com.nous.ahcc.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.nous.ahcc.domain.model.AgentReplyParts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class TtsVoiceChoice(
    val name: String,
    val language: String,
    val label: String,
)

/** Beeps and speaks the agent's reply text in the main chat. */
class ChatCuePlayer(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ready = false
    private var pending: String? = null
    private var englishVoice = ""
    private var russianVoice = ""
    private var speechJob: Job? = null
    private var utteranceWait: CompletableDeferred<Unit>? = null
    private var utteranceId: String? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts init status=$status")
            return
        }
        ready = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishUtterance(utteranceId)
            override fun onError(utteranceId: String?) = finishUtterance(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finishUtterance(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishUtterance(utteranceId)
        })
        pending?.let {
            pending = null
            speechJob = scope.launch { speakNow(it) }
        }
    }

    fun setPreferredVoices(english: String, russian: String) {
        englishVoice = english.trim()
        russianVoice = russian.trim()
    }

    fun listVoices(): List<TtsVoiceChoice> {
        val engine = tts
        if (!ready) return emptyList()
        listOf(Locale.US, Locale.UK, Locale("ru", "RU")).forEach { locale ->
            runCatching { engine.language = locale }
        }
        return engine.voices.orEmpty()
            .asSequence()
            .filter { voice ->
                val lang = voice.locale.language.lowercase(Locale.ROOT)
                lang == "en" || lang == "ru"
            }
            .map { voice ->
                val lang = voice.locale.language.lowercase(Locale.ROOT)
                TtsVoiceChoice(
                    name = voice.name,
                    language = lang,
                    label = voiceLabel(voice),
                )
            }
            .distinctBy { it.name }
            .sortedWith(compareBy({ it.language }, { it.label.lowercase(Locale.ROOT) }))
            .toList()
    }

    fun preview(voiceName: String, language: String) {
        val sample = if (language == "ru") SAMPLE_RU else SAMPLE_EN
        scope.launch {
            if (!ready) return@launch
            applyVoice(language, voiceName)
            tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "ahcc-preview")
        }
    }

    fun beep() {
        scope.launch { playTones(1) }
    }

    suspend fun beepAwait() {
        playTones(1)
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        speechJob?.cancel()
        speechJob = scope.launch { speakNow(clean) }
    }

    fun doubleBeepThenSpeak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        speechJob?.cancel()
        speechJob = scope.launch {
            playTones(2)
            speakNow(clean)
        }
    }

    fun stopSpeaking() {
        speechJob?.cancel()
        runCatching { tts.stop() }
    }

    fun shutdown() {
        scope.cancel()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private suspend fun speakNow(text: String) {
        if (!ready) {
            pending = text
            return
        }
        val runs = AgentReplyParts.speechRuns(text)
        for (run in runs) {
            val preferred = if (run.language == "ru") russianVoice else englishVoice
            applyVoice(run.language, preferred)
            val id = "ahcc-reply-${System.nanoTime()}"
            val done = CompletableDeferred<Unit>()
            utteranceId = id
            utteranceWait = done
            val queued = tts.speak(run.text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (queued == TextToSpeech.ERROR) {
                utteranceWait = null
                continue
            }
            withTimeoutOrNull(120_000) { done.await() }
            if (utteranceId == id) utteranceWait = null
        }
    }

    private fun finishUtterance(id: String?) {
        if (id != null && id == utteranceId) {
            utteranceWait?.complete(Unit)
        }
    }

    private fun applyVoice(language: String, voiceName: String) {
        val match = voiceName.takeIf { it.isNotBlank() }?.let { name ->
            tts.voices?.firstOrNull { it.name == name }
        }
        if (match != null) {
            tts.voice = match
            tts.language = match.locale
            return
        }
        val locale = if (language == "ru") Locale("ru", "RU") else Locale.US
        tts.language = locale
        val fallback = tts.voices
            ?.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            ?.maxByOrNull { it.quality }
        if (fallback != null) tts.voice = fallback
    }

    private fun voiceLabel(voice: Voice): String {
        val net = if (voice.isNetworkConnectionRequired) "online" else "offline"
        val features = voice.features.orEmpty().joinToString(" ").lowercase(Locale.ROOT)
        val gender = when {
            features.contains("female") || voice.name.contains("-f-", ignoreCase = true) -> "♀"
            features.contains("male") || voice.name.contains("-m-", ignoreCase = true) -> "♂"
            else -> "·"
        }
        return "${voice.locale.toLanguageTag()} $gender ${voice.name.substringAfterLast('-')} ($net)"
    }

    private suspend fun playTones(count: Int) {
        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        try {
            repeat(count) { index ->
                generator.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
                delay(if (index == count - 1) 220L else 320L)
            }
        } finally {
            generator.release()
        }
    }

    companion object {
        private const val TAG = "AHCC-Cue"
        private const val SAMPLE_EN = "This is a sample of the selected English voice."
        private const val SAMPLE_RU = "Это образец выбранного русского голоса."
    }
}
