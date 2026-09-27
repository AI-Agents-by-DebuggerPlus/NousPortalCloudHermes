package com.nous.ahcc.headset

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HeadsetButtonEvent(
    val label: String,
    val source: String,
    val kind: String,
    val at: String,
    val viaDoublePlay: Boolean = false,
    val tapCount: Int = 1,
)

data class HeadsetTestState(
    val captureOn: Boolean = false,
    val pressCount: Int = 0,
    val nextCount: Int = 0,
    val singlePlayCount: Int = 0,
    val doublePlayCount: Int = 0,
    val triplePlayCount: Int = 0,
    val pendingPlayCount: Int = 0,
    /** Каждое принятое нажатие Play, без учёта пауз. */
    val rawPlayCount: Int = 0,
    val multiplicityCount: Int = 0,
    val timerRunning: Boolean = false,
    val timerResetAtMs: Long = 0L,
    val multiplicityWindowSecText: String = "2",
    val multiplicitySettleSecText: String = "5",
    val playLockoutSecText: String = "5",
    val lastLabel: String = "",
    val lastAt: String = "",
    val lastKind: String = "",
    val eventLog: List<String> = emptyList(),
    val debounceEnabled: Boolean = true,
    val debounceIntervalMs: Long = HeadsetButtonPreferences.DEFAULT_DEBOUNCE_MS,
    val nextDoubleTapMs: Long = HeadsetButtonPreferences.DEFAULT_NEXT_DOUBLE_TAP_MS,
    val debounceIntervalText: String = HeadsetButtonPreferences.DEFAULT_DEBOUNCE_MS.toString(),
    val nextDoubleTapText: String = HeadsetButtonPreferences.DEFAULT_NEXT_DOUBLE_TAP_MS.toString()
)

/**
 * Hub жестов гарнитуры.
 * Нажатия Play в окне кратности увеличивают счётчик кратности.
 * После паузы зачёта серия уходит в счётчик 1×, 2× или 3×.
 * MEDIA_NEXT → аппаратный Next.
 */
class HeadsetButtonHub(
    private val buttonPreferences: HeadsetButtonPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var lastCommittedKey: String? = null
    private var lastCommittedAtMs: Long = 0L
    private var lastGestureAtMs: Long = 0L
    private var suppressPlayUntilMs: Long = 0L
    private var lastEmittedTaps: Int = 0
    private var burstCount: Int = 0
    private var burstJob: Job? = null
    private var burstGeneration: Int = 0
    private var pendingPlaySource: String = "hardware"
    private var pendingPlayLabel: String = "MEDIA_PLAY"
    private var playLockedUntilMs: Long = 0L

    private val _state = MutableStateFlow(
        HeadsetTestState(
            debounceEnabled = buttonPreferences.debounceEnabled,
            debounceIntervalMs = buttonPreferences.debounceIntervalMs,
            nextDoubleTapMs = buttonPreferences.nextDoubleTapMs,
            debounceIntervalText = buttonPreferences.debounceIntervalMs.toString(),
            nextDoubleTapText = buttonPreferences.nextDoubleTapMs.toString(),
            multiplicityWindowSecText = (buttonPreferences.multiplicityWindowMs / 1000).toString(),
            multiplicitySettleSecText = (buttonPreferences.multiplicitySettleMs / 1000).toString(),
            playLockoutSecText = (buttonPreferences.playLockoutMs / 1000).toString(),
        )
    )
    val state: StateFlow<HeadsetTestState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadsetButtonEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<HeadsetButtonEvent> = _events.asSharedFlow()

    /** When false (BT test screen), chat must not start voice on Play. */
    @Volatile
    var voiceGesturesEnabled: Boolean = true

    /** Play tap test screen is open: hardware Play must not start or send a voice message. */
    @Volatile
    var playTestActive: Boolean = false

    private val voiceGestureLock = Any()
    private var lastVoiceGestureConsumedAtMs = 0L

    /**
     * Process-wide gate so a single committed Play is handled once even if
     * multiple collectors listen to [events].
     */
    fun tryConsumeVoiceGesture(minIntervalMs: Long = 700L): Boolean {
        if (!voiceGesturesEnabled) return false
        val now = System.currentTimeMillis()
        synchronized(voiceGestureLock) {
            if (now - lastVoiceGestureConsumedAtMs < minIntervalMs) {
                Log.d(TAG, "voice gesture consumed already (${now - lastVoiceGestureConsumedAtMs}ms ago)")
                return false
            }
            lastVoiceGestureConsumedAtMs = now
            return true
        }
    }

    init {
        scope.launch {
            buttonPreferences.state.collect { prefs ->
                _state.update {
                    it.copy(
                        debounceEnabled = prefs.debounceEnabled,
                        debounceIntervalMs = prefs.debounceIntervalMs,
                        nextDoubleTapMs = prefs.nextDoubleTapMs,
                    )
                }
            }
        }
    }

    fun setCaptureOn(on: Boolean) {
        _state.update { it.copy(captureOn = on) }
    }

    fun setDebounceEnabled(enabled: Boolean) {
        buttonPreferences.setDebounceEnabled(enabled)
    }

    fun onDebounceIntervalTextChange(text: String) {
        _state.update { it.copy(debounceIntervalText = text.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun commitDebounceInterval() {
        val ms = _state.value.debounceIntervalText.toLongOrNull()
            ?: buttonPreferences.debounceIntervalMs
        buttonPreferences.setDebounceIntervalMs(ms)
        _state.update {
            it.copy(debounceIntervalText = buttonPreferences.debounceIntervalMs.toString())
        }
    }

    fun onNextDoubleTapTextChange(text: String) {
        _state.update { it.copy(nextDoubleTapText = text.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun commitNextDoubleTapInterval() {
        val ms = _state.value.nextDoubleTapText.toLongOrNull()
            ?: buttonPreferences.nextDoubleTapMs
        buttonPreferences.setNextDoubleTapMs(ms)
        _state.update {
            it.copy(nextDoubleTapText = buttonPreferences.nextDoubleTapMs.toString())
        }
    }

    fun onMultiplicityWindowTextChange(text: String) {
        _state.update { it.copy(multiplicityWindowSecText = text.filter { ch -> ch.isDigit() }.take(3)) }
    }

    fun commitMultiplicityWindow() {
        val sec = _state.value.multiplicityWindowSecText.toLongOrNull()
            ?: (buttonPreferences.multiplicityWindowMs / 1000)
        buttonPreferences.setMultiplicityWindowMs(sec * 1000)
        _state.update {
            it.copy(multiplicityWindowSecText = (buttonPreferences.multiplicityWindowMs / 1000).toString())
        }
    }

    fun onMultiplicitySettleTextChange(text: String) {
        _state.update { it.copy(multiplicitySettleSecText = text.filter { ch -> ch.isDigit() }.take(3)) }
    }

    fun commitMultiplicitySettle() {
        val sec = _state.value.multiplicitySettleSecText.toLongOrNull()
            ?: (buttonPreferences.multiplicitySettleMs / 1000)
        buttonPreferences.setMultiplicitySettleMs(sec * 1000)
        _state.update {
            it.copy(multiplicitySettleSecText = (buttonPreferences.multiplicitySettleMs / 1000).toString())
        }
    }

    fun onPlayLockoutTextChange(text: String) {
        _state.update { it.copy(playLockoutSecText = text.filter { ch -> ch.isDigit() }.take(3)) }
    }

    fun commitPlayLockout() {
        val sec = _state.value.playLockoutSecText.toLongOrNull()
            ?: (buttonPreferences.playLockoutMs / 1000)
        buttonPreferences.setPlayLockoutMs(sec * 1000)
        _state.update {
            it.copy(playLockoutSecText = (buttonPreferences.playLockoutMs / 1000).toString())
        }
    }

    fun resetCounter() {
        scope.launch {
            mutex.withLock {
                burstGeneration++
                burstJob?.cancel()
                burstJob = null
                burstCount = 0
            }
            _state.update {
                it.copy(
                    pressCount = 0,
                    nextCount = 0,
                    singlePlayCount = 0,
                    doublePlayCount = 0,
                    triplePlayCount = 0,
                    pendingPlayCount = 0,
                    rawPlayCount = 0,
                    multiplicityCount = 0,
                    timerRunning = false,
                    lastLabel = "",
                    lastAt = "",
                    lastKind = "",
                    eventLog = emptyList()
                )
            }
            Log.i(TAG, "Play/Next counters reset")
        }
    }

    fun noteRawPlay() {
        _state.update { it.copy(rawPlayCount = it.rawPlayCount + 1) }
    }

    fun notifyButton(buttonLabel: String, source: String = "native") {
        val label = HeadsetButtonNames.normalize(buttonLabel)
        scope.launch {
            val kind = eventKind(source)
            val now = System.currentTimeMillis()
            val delta = if (lastGestureAtMs == 0L) -1L else now - lastGestureAtMs
            Log.d(TAG, "$kind in: $label via $source · Δ=${if (delta < 0) "—" else "${delta}ms"}")

            if (HeadsetButtonNames.isBtPlayGestureLabel(label)) {
                handlePlayGesture(label, source, now)
                return@launch
            }

            if (label == "MEDIA_NEXT" || label == "NEXT") {
                handleHardwareNext(label, source, now, kind)
                return@launch
            }

            mutex.withLock { cancelPendingPlayLocked() }
            recordOtherEvent(label, source, kind)
        }
    }

    fun simulate(label: String) {
        notifyButton(label, source = "ui-simulate")
    }

    /** Сразу фиксирует 1, 2 или 3 нажатия Play — для экрана проверки жеста. */
    fun simulateTapCount(taps: Int) {
        scope.launch {
            val count = taps.coerceIn(1, 3)
            mutex.withLock {
                cancelPendingPlayLocked()
                lastEmittedTaps = count
            }
            repeat(count) { noteRawPlay() }
            recordPlayEvent("MEDIA_PLAY", "ui-simulate", count)
        }
    }

    private suspend fun handleHardwareNext(
        label: String,
        source: String,
        now: Long,
        kind: String
    ) {
        val prefs = buttonPreferences.state.value
        mutex.withLock {
            cancelPendingPlayLocked()
            lastGestureAtMs = 0L
            lastCommittedKey = BT_NEXT_KEY
            lastCommittedAtMs = now
            suppressPlayUntilMs = now + prefs.nextDoubleTapMs
        }
        Log.i(TAG, "$kind: $label via $source → Next (suppress Play ${prefs.nextDoubleTapMs}ms)")
        recordNextEvent(source = source, kind = kind, viaDoublePlay = false)
    }

    private suspend fun handlePlayGesture(label: String, source: String, now: Long) {
        val kind = eventKind(source)
        val update = mutex.withLock {
            if (now < playLockedUntilMs || now < suppressPlayUntilMs) {
                BurstUpdate.Suppressed
            } else if (isSamePressEcho(label, source, now)) {
                BurstUpdate.Echo
            } else {
                val window = buttonPreferences.multiplicityWindowMs
                val closed = if (burstCount > 0 && now - lastGestureAtMs > window) {
                    finishBurstLocked()
                } else {
                    null
                }
                burstCount = if (closed != null || burstCount == 0) 1 else burstCount + 1
                pendingPlaySource = source
                pendingPlayLabel = label
                lastGestureAtMs = now
                val triple = if (burstCount >= 3) {
                    burstGeneration++
                    burstJob?.cancel()
                    burstJob = null
                    finishBurstLocked()
                } else {
                    armSettleTimerLocked()
                    null
                }
                BurstUpdate.Counted(closed, burstCount, now, triple)
            }
        }
        when (update) {
            BurstUpdate.Suppressed -> Log.d(TAG, "Suppressed Play after Next: $label ($source)")
            BurstUpdate.Echo -> Log.d(TAG, "Companion Play ignored: $label ($source)")
            is BurstUpdate.Counted -> {
                update.closed?.let { (count, commitSource) ->
                    Log.i(TAG, "window closed → ${count}× Play via $commitSource")
                    recordPlayEvent("MEDIA_PLAY", commitSource, count)
                }
                noteRawPlay()
                update.triple?.let { (count, commitSource) ->
                    Log.i(TAG, "triple now → ${count}× Play via $commitSource")
                    recordPlayEvent("MEDIA_PLAY", commitSource, count)
                }
                Log.d(TAG, "$kind: multiplicity ${update.multiplicity} via $source")
                _state.update {
                    if (update.triple != null) {
                        it.copy(multiplicityCount = 0, pendingPlayCount = 0, timerRunning = false)
                    } else {
                        it.copy(
                            multiplicityCount = update.multiplicity,
                            pendingPlayCount = update.multiplicity,
                            timerRunning = true,
                            timerResetAtMs = update.timerAtMs,
                        )
                    }
                }
            }
        }
    }

    /** После паузы зачёта фиксирует серию и обнуляет счётчик кратности. */
    private fun armSettleTimerLocked() {
        val generation = ++burstGeneration
        val wait = buttonPreferences.multiplicitySettleMs
        burstJob?.cancel()
        burstJob = scope.launch {
            delay(wait)
            val commit = mutex.withLock {
                if (generation != burstGeneration || burstCount == 0) {
                    null
                } else {
                    finishBurstLocked()
                }
            }
            if (commit != null) {
                Log.i(TAG, "settle → ${commit.first}× Play via ${commit.second}")
                recordPlayEvent("MEDIA_PLAY", commit.second, commit.first)
                val lockoutMs = buttonPreferences.playLockoutMs
                mutex.withLock {
                    playLockedUntilMs = System.currentTimeMillis() + lockoutMs
                }
                Log.i(TAG, "Play locked for ${lockoutMs}ms after settle")
                _state.update {
                    it.copy(multiplicityCount = 0, pendingPlayCount = 0, timerRunning = false)
                }
            }
        }
    }

    private fun finishBurstLocked(): Pair<Int, String>? {
        if (burstCount == 0) return null
        val count = burstCount
        val source = pendingPlaySource
        burstCount = 0
        lastEmittedTaps = count
        lastCommittedKey = BT_PLAY_KEY
        lastCommittedAtMs = System.currentTimeMillis()
        return count to source
    }

    /**
     * One physical click often arrives twice (PLAY+PAUSE, or mediaButton + onPlay).
     * A second PLAY from the same source is the next tap, even if it is quick.
     */
    private fun isSamePressEcho(label: String, source: String, now: Long): Boolean {
        if (lastGestureAtMs == 0L) return false
        val delta = now - lastGestureAtMs
        if (delta < DUPLICATE_MS) return true
        if (delta >= ECHO_MS) return false
        val playPausePair = isPauseLabel(label) != isPauseLabel(pendingPlayLabel)
        return source != pendingPlaySource || playPausePair
    }

    private fun isPauseLabel(label: String): Boolean {
        val n = HeadsetButtonNames.normalize(label)
        return n == "MEDIA_PAUSE" || n == "PAUSE"
    }

    private fun cancelPendingPlayLocked() {
        burstGeneration++
        burstJob?.cancel()
        burstJob = null
        burstCount = 0
        lastEmittedTaps = 0
        lastGestureAtMs = 0L
        pendingPlaySource = "hardware"
        pendingPlayLabel = "MEDIA_PLAY"
        _state.update {
            it.copy(pendingPlayCount = 0, multiplicityCount = 0, timerRunning = false)
        }
    }

    private sealed class BurstUpdate {
        data object Suppressed : BurstUpdate()
        data object Echo : BurstUpdate()
        data class Counted(
            val closed: Pair<Int, String>?,
            val multiplicity: Int,
            val timerAtMs: Long,
            val triple: Pair<Int, String>? = null,
        ) : BurstUpdate()
    }

    private suspend fun recordPlayEvent(label: String, source: String, tapCount: Int = 1) {
        val kind = eventKind(source)
        val display = when (tapCount) {
            3 -> "TriplePlay"
            2 -> "DoublePlay"
            else -> HeadsetButtonNames.displayLabel(
                if (HeadsetButtonNames.isBtPlayLabel(label)) label else "MEDIA_PLAY"
            )
        }
        val now = System.currentTimeMillis()
        val at = timeFmt.format(Date(now))
        _events.emit(
            HeadsetButtonEvent(label = display, source = source, kind = kind, at = at, tapCount = tapCount)
        )
        _state.update { current ->
            val playCount = current.pressCount + 1
            val line = "$at  [$kind]  $display ×$tapCount  (#$playCount)"
            current.copy(
                pressCount = playCount,
                singlePlayCount = current.singlePlayCount + if (tapCount == 1) 1 else 0,
                doublePlayCount = current.doublePlayCount + if (tapCount == 2) 1 else 0,
                triplePlayCount = current.triplePlayCount + if (tapCount >= 3) 1 else 0,
                pendingPlayCount = 0,
                lastLabel = "$display · $kind",
                lastAt = at,
                lastKind = kind,
                eventLog = (listOf(line) + current.eventLog).take(MAX_EVENTS)
            )
        }
        Log.i(TAG, "BT Play $display via $source ($kind)")
    }

    private suspend fun recordNextEvent(source: String, kind: String, viaDoublePlay: Boolean) {
        val display = if (viaDoublePlay) "Next (2×Play)" else "Next"
        val now = System.currentTimeMillis()
        val at = timeFmt.format(Date(now))
        _events.emit(
            HeadsetButtonEvent(
                label = display,
                source = source,
                kind = kind,
                at = at,
                viaDoublePlay = viaDoublePlay
            )
        )
        _state.update { current ->
            val nextCount = current.nextCount + 1
            val line = "$at  [$kind]  $display  (#$nextCount)"
            current.copy(
                nextCount = nextCount,
                lastLabel = "$display · $kind",
                lastAt = at,
                lastKind = kind,
                eventLog = (listOf(line) + current.eventLog).take(MAX_EVENTS)
            )
        }
        Log.i(TAG, "BT Next $display via $source ($kind)")
    }

    private suspend fun recordOtherEvent(label: String, source: String, kind: String) {
        val display = HeadsetButtonNames.displayLabel(label)
        val now = System.currentTimeMillis()
        val at = timeFmt.format(Date(now))
        _events.emit(HeadsetButtonEvent(label = display, source = source, kind = kind, at = at))
        _state.update { current ->
            val line = "$at  [$kind]  $display"
            current.copy(
                lastLabel = "$display · $kind",
                lastAt = at,
                lastKind = kind,
                eventLog = (listOf(line) + current.eventLog).take(MAX_EVENTS)
            )
        }
        Log.i(TAG, "BT other $display via $source ($kind)")
    }

    companion object {
        private const val TAG = "AHCC-BT"
        private const val BT_PLAY_KEY = "BT_PLAY"
        private const val BT_NEXT_KEY = "BT_NEXT"
        private const val MAX_EVENTS = 40
        /** Identical callbacks of one click. */
        private const val DUPLICATE_MS = 45L
        /** PLAY+PAUSE or mediaButton+onPlay of one click. A second PLAY is a new tap. */
        private const val ECHO_MS = 220L

        fun eventKind(source: String): String {
            val s = source.lowercase()
            return if (
                s.contains("hardware") ||
                s.contains("mediabuttonevent") ||
                s.contains("callback-on") ||
                s == "native"
            ) {
                "HARDWARE"
            } else {
                "SIMULATED"
            }
        }
    }
}
