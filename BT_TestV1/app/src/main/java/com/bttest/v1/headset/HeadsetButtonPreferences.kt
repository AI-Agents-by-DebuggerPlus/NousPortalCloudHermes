package com.bttest.v1.headset

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HeadsetButtonPrefs(
    val debounceEnabled: Boolean = true,
    val debounceIntervalMs: Long = HeadsetButtonPreferences.DEFAULT_DEBOUNCE_MS,
    val nextDoubleTapMs: Long = HeadsetButtonPreferences.DEFAULT_NEXT_DOUBLE_TAP_MS
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

    private fun load(): HeadsetButtonPrefs = HeadsetButtonPrefs(
        debounceEnabled = prefs.getBoolean(KEY_DEBOUNCE_ENABLED, true),
        debounceIntervalMs = prefs.getLong(KEY_DEBOUNCE_INTERVAL_MS, DEFAULT_DEBOUNCE_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        nextDoubleTapMs = prefs.getLong(KEY_NEXT_DOUBLE_TAP_MS, DEFAULT_NEXT_DOUBLE_TAP_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_NEXT_DOUBLE_TAP_MS = 400L
        const val MIN_INTERVAL_MS = 50L
        const val MAX_INTERVAL_MS = 5_000L
        private const val PREFS_NAME = "bttest_v1_headset_button_prefs"
        private const val KEY_DEBOUNCE_ENABLED = "debounce_enabled"
        private const val KEY_DEBOUNCE_INTERVAL_MS = "debounce_interval_ms"
        private const val KEY_NEXT_DOUBLE_TAP_MS = "next_double_tap_ms"
    }
}

