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
    val viaDoublePlay: Boolean = false
)

data class HeadsetTestState(
    val captureOn: Boolean = false,
    val pressCount: Int = 0,
    val nextCount: Int = 0,
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
 * Hub + жесты Play/Next как в AndroidEnglishTutor:
 * одиночный Play-жест → Play; второй в окне nextDoubleTapMs → Next;
 * MEDIA_NEXT → Next + suppress companion Play.
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
    private var pendingPlayJob: Job? = null
    private var pendingPlaySource: String? = null
    private var pendingPlayLabel: String? = null
    private var pendingGeneration: Int = 0

    private val _state = MutableStateFlow(
        HeadsetTestState(
            debounceEnabled = buttonPreferences.debounceEnabled,
            debounceIntervalMs = buttonPreferences.debounceIntervalMs,
            nextDoubleTapMs = buttonPreferences.nextDoubleTapMs,
            debounceIntervalText = buttonPreferences.debounceIntervalMs.toString(),
            nextDoubleTapText = buttonPreferences.nextDoubleTapMs.toString()
        )
    )
    val state: StateFlow<HeadsetTestState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadsetButtonEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<HeadsetButtonEvent> = _events.asSharedFlow()

    /** When false (BT test screen), chat must not start voice on Play. */
    @Volatile
    var voiceGesturesEnabled: Boolean = true

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
                        nextDoubleTapMs = prefs.nextDoubleTapMs
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

    fun resetCounter() {
        _state.update {
            it.copy(
                pressCount = 0,
                nextCount = 0,
                lastLabel = "",
                lastAt = "",
                lastKind = "",
                eventLog = emptyList()
            )
        }
        Log.i(TAG, "Play/Next counters reset")
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
        val prefs = buttonPreferences.state.value
        val doubleTapMs = prefs.nextDoubleTapMs
        val kind = eventKind(source)

        val action = mutex.withLock {
            if (now < suppressPlayUntilMs) {
                PlayAction.SuppressedAfterNext
            } else if (
                pendingPlayJob != null &&
                lastGestureAtMs > 0L &&
                now - lastGestureAtMs <= doubleTapMs
            ) {
                // Same physical press often emits PLAY + PAUSE (or mediaButton + onPlay)
                // within a few dozen ms — do not treat as double-tap → Next.
                if (now - lastGestureAtMs < COALESCE_MS) {
                    PlayAction.CompanionIgnored
                } else {
                    cancelPendingPlayLocked()
                    lastGestureAtMs = 0L
                    lastCommittedKey = BT_NEXT_KEY
                    lastCommittedAtMs = now
                    suppressPlayUntilMs = now + doubleTapMs
                    PlayAction.CommitNext
                }
            } else if (
                prefs.debounceEnabled &&
                (lastCommittedKey == BT_PLAY_KEY || lastCommittedKey == BT_NEXT_KEY) &&
                now - lastCommittedAtMs < prefs.debounceIntervalMs
            ) {
                PlayAction.Debounce
            } else {
                lastGestureAtMs = now
                pendingPlaySource = source
                pendingPlayLabel = label
                val generation = ++pendingGeneration
                pendingPlayJob = scope.launch {
                    delay(doubleTapMs)
                    val commit = mutex.withLock {
                        if (generation != pendingGeneration || pendingPlaySource == null) {
                            null
                        } else {
                            val commitSource = pendingPlaySource!!
                            val commitLabel = pendingPlayLabel!!
                            pendingPlayJob = null
                            pendingPlaySource = null
                            pendingPlayLabel = null
                            lastCommittedKey = BT_PLAY_KEY
                            lastCommittedAtMs = System.currentTimeMillis()
                            commitLabel to commitSource
                        }
                    }
                    if (commit != null) {
                        recordPlayEvent(commit.first, commit.second)
                    }
                }
                PlayAction.WaitForDouble(doubleTapMs)
            }
        }

        when (action) {
            PlayAction.Debounce -> Log.d(TAG, "Debounced: $label ($source)")
            PlayAction.SuppressedAfterNext ->
                Log.d(TAG, "Suppressed Play after Next: $label ($source)")
            PlayAction.CompanionIgnored ->
                Log.d(TAG, "Companion Play ignored (<${COALESCE_MS}ms): $label ($source)")
            PlayAction.CommitNext -> {
                Log.i(
                    TAG,
                    "$kind: double gesture ($label) via $source → Next (window ${prefs.nextDoubleTapMs}ms)"
                )
                recordNextEvent(source = source, kind = kind, viaDoublePlay = true)
            }
            is PlayAction.WaitForDouble ->
                Log.d(TAG, "Play pending (${action.windowMs}ms) for double-tap: $label ($source)")
        }
    }

    private fun cancelPendingPlayLocked() {
        pendingGeneration++
        pendingPlayJob?.cancel()
        pendingPlayJob = null
        pendingPlaySource = null
        pendingPlayLabel = null
    }

    private suspend fun recordPlayEvent(label: String, source: String) {
        val kind = eventKind(source)
        val display = HeadsetButtonNames.displayLabel(
            if (HeadsetButtonNames.isBtPlayLabel(label)) label else "MEDIA_PLAY"
        )
        val now = System.currentTimeMillis()
        val at = timeFmt.format(Date(now))
        _events.emit(HeadsetButtonEvent(label = display, source = source, kind = kind, at = at))
        _state.update { current ->
            val playCount = current.pressCount + 1
            val line = "$at  [$kind]  $display  (#$playCount)"
            current.copy(
                pressCount = playCount,
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

    private sealed class PlayAction {
        data object Debounce : PlayAction()
        data object SuppressedAfterNext : PlayAction()
        data object CompanionIgnored : PlayAction()
        data object CommitNext : PlayAction()
        data class WaitForDouble(val windowMs: Long) : PlayAction()
    }

    companion object {
        private const val TAG = "AHCC-BT"
        private const val BT_PLAY_KEY = "BT_PLAY"
        private const val BT_NEXT_KEY = "BT_NEXT"
        private const val MAX_EVENTS = 40
        /** Collapse PLAY+PAUSE / dual callbacks from one physical press. */
        private const val COALESCE_MS = 280L

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
