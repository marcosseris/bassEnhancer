package com.marcos.bassenhancer.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RunState { STOPPED, RUNNING, PAUSED, ERROR }

/**
 * Tiny process-wide bridge between the service and the UI. A bound service or a
 * broadcast would be heavier than this is worth: both live in the same process.
 */
object ServiceState {
    private val _state = MutableStateFlow(RunState.STOPPED)
    val state: StateFlow<RunState> = _state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Live band level in dBFS, for the meter. Updated a few times a second. */
    private val _levelDb = MutableStateFlow(-90f)
    val levelDb: StateFlow<Float> = _levelDb

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    internal fun set(state: RunState, message: String? = null) {
        _state.value = state
        _message.value = message
        if (state != RunState.RUNNING) {
            _levelDb.value = -90f
            _amplitude.value = 0f
        }
    }

    internal fun publishLevels(db: Float, amplitude: Float) {
        _levelDb.value = db
        _amplitude.value = amplitude
    }
}
