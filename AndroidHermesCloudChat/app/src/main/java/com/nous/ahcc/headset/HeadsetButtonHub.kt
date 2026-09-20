package com.nous.ahcc.headset

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val at: String
)

data class HeadsetTestState(
    val captureOn: Boolean = false,
    val pressCount: Int = 0,
    val lastLabel: String = "",
    val lastAt: String = "",
    val eventLog: List<String> = emptyList()
)

/**
 * Hub for Bluetooth headset media buttons (test isolation mode like AndroidChat).
 */
class HeadsetButtonHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var lastKey: String? = null
    private var lastAtMs: Long = 0L
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _state = MutableStateFlow(HeadsetTestState())
    val state: StateFlow<HeadsetTestState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HeadsetButtonEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<HeadsetButtonEvent> = _events.asSharedFlow()

    fun setCaptureOn(on: Boolean) {
        _state.update { it.copy(captureOn = on) }
    }

    fun resetCounter() {
        _state.update {
            it.copy(
                pressCount = 0,
                lastLabel = "",
                lastAt = "",
                eventLog = emptyList()
            )
        }
    }

    fun notifyButton(buttonLabel: String, source: String = "native") {
        val label = HeadsetButtonNames.normalize(buttonLabel)
        val now = System.currentTimeMillis()
        scope.launch {
            mutex.withLock {
                if (label == lastKey && now - lastAtMs < DEBOUNCE_MS) {
                    Log.d(TAG, "Debounced $label ($source)")
                    return@launch
                }
                lastKey = label
                lastAtMs = now
            }
            val at = timeFmt.format(Date(now))
            val event = HeadsetButtonEvent(label = label, source = source, at = at)
            _events.emit(event)
            _state.update { st ->
                val line = "$at  $label  ($source)  #${st.pressCount + 1}"
                st.copy(
                    pressCount = st.pressCount + 1,
                    lastLabel = label,
                    lastAt = at,
                    eventLog = (listOf(line) + st.eventLog).take(40)
                )
            }
            Log.i(TAG, "BT button $label via $source")
        }
    }

    fun simulate(label: String) {
        notifyButton(label, source = "ui-simulate")
    }

    companion object {
        private const val TAG = "AHCC-BT"
        private const val DEBOUNCE_MS = 500L
    }
}
