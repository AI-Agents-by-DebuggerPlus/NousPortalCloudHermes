package com.nous.ahcc.headset

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HeadsetButtonPrefs(
    val debounceEnabled: Boolean = true,
    val debounceIntervalMs: Long = HeadsetButtonPreferences.DEFAULT_DEBOUNCE_MS,
    val nextDoubleTapMs: Long = HeadsetButtonPreferences.DEFAULT_NEXT_DOUBLE_TAP_MS,
    val multiplicityWindowMs: Long = HeadsetButtonPreferences.DEFAULT_MULTIPLICITY_WINDOW_MS,
    val multiplicitySettleMs: Long = HeadsetButtonPreferences.DEFAULT_MULTIPLICITY_SETTLE_MS,
    val playLockoutMs: Long = HeadsetButtonPreferences.DEFAULT_PLAY_LOCKOUT_MS,
)

/**
 * Настройки debounce / окна двойного Play → Next (как в AndroidEnglishTutor).
 */
class HeadsetButtonPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HeadsetButtonPrefs> = _state.asStateFlow()

    val debounceEnabled: Boolean get() = _state.value.debounceEnabled
    val debounceIntervalMs: Long get() = _state.value.debounceIntervalMs
    val nextDoubleTapMs: Long get() = _state.value.nextDoubleTapMs
    val multiplicityWindowMs: Long get() = _state.value.multiplicityWindowMs
    val multiplicitySettleMs: Long get() = _state.value.multiplicitySettleMs
    val playLockoutMs: Long get() = _state.value.playLockoutMs

    fun setDebounceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBOUNCE_ENABLED, enabled).apply()
        _state.update { it.copy(debounceEnabled = enabled) }
    }

    fun setDebounceIntervalMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        prefs.edit().putLong(KEY_DEBOUNCE_INTERVAL_MS, clamped).apply()
        _state.update { it.copy(debounceIntervalMs = clamped) }
    }

    fun setNextDoubleTapMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        prefs.edit().putLong(KEY_NEXT_DOUBLE_TAP_MS, clamped).apply()
        _state.update { it.copy(nextDoubleTapMs = clamped) }
    }

    fun setMultiplicityWindowMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_WINDOW_MS)
        prefs.edit().putLong(KEY_MULTIPLICITY_WINDOW_MS, clamped).apply()
        _state.update { it.copy(multiplicityWindowMs = clamped) }
    }

    fun setMultiplicitySettleMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_SETTLE_MS)
        prefs.edit().putLong(KEY_MULTIPLICITY_SETTLE_MS, clamped).apply()
        _state.update { it.copy(multiplicitySettleMs = clamped) }
    }

    fun setPlayLockoutMs(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_SETTLE_MS)
        prefs.edit().putLong(KEY_PLAY_LOCKOUT_MS, clamped).apply()
        _state.update { it.copy(playLockoutMs = clamped) }
    }

    private fun load(): HeadsetButtonPrefs = HeadsetButtonPrefs(
        debounceEnabled = prefs.getBoolean(KEY_DEBOUNCE_ENABLED, true),
        debounceIntervalMs = prefs.getLong(KEY_DEBOUNCE_INTERVAL_MS, DEFAULT_DEBOUNCE_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        nextDoubleTapMs = prefs.getLong(KEY_NEXT_DOUBLE_TAP_MS, DEFAULT_NEXT_DOUBLE_TAP_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        multiplicityWindowMs = prefs.getLong(KEY_MULTIPLICITY_WINDOW_MS, DEFAULT_MULTIPLICITY_WINDOW_MS)
            .coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_WINDOW_MS),
        multiplicitySettleMs = prefs.getLong(KEY_MULTIPLICITY_SETTLE_MS, DEFAULT_MULTIPLICITY_SETTLE_MS)
            .coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_SETTLE_MS),
        playLockoutMs = prefs.getLong(KEY_PLAY_LOCKOUT_MS, DEFAULT_PLAY_LOCKOUT_MS)
            .coerceIn(MIN_MULTIPLICITY_MS, MAX_MULTIPLICITY_SETTLE_MS),
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_NEXT_DOUBLE_TAP_MS = 400L
        const val MIN_INTERVAL_MS = 50L
        const val MAX_INTERVAL_MS = 5_000L
        const val DEFAULT_MULTIPLICITY_WINDOW_MS = 2_000L
        const val DEFAULT_MULTIPLICITY_SETTLE_MS = 5_000L
        const val DEFAULT_PLAY_LOCKOUT_MS = 5_000L
        const val MIN_MULTIPLICITY_MS = 500L
        const val MAX_MULTIPLICITY_WINDOW_MS = 30_000L
        const val MAX_MULTIPLICITY_SETTLE_MS = 120_000L
        private const val PREFS_NAME = "ahcc_headset_button_prefs"
        private const val KEY_DEBOUNCE_ENABLED = "debounce_enabled"
        private const val KEY_DEBOUNCE_INTERVAL_MS = "debounce_interval_ms"
        private const val KEY_NEXT_DOUBLE_TAP_MS = "next_double_tap_ms"
        private const val KEY_MULTIPLICITY_WINDOW_MS = "multiplicity_window_ms"
        private const val KEY_MULTIPLICITY_SETTLE_MS = "multiplicity_settle_ms"
        private const val KEY_PLAY_LOCKOUT_MS = "play_lockout_ms"
    }
}
