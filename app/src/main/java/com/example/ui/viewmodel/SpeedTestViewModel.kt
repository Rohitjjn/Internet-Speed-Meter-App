package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesManager
import com.example.data.SpeedTestResult
import com.example.services.SpeedTestEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpeedTestPhase {
    IDLE,
    PING,
    DOWNLOAD,
    UPLOAD,
    DONE,
    ERROR
}

/**
 * UI state of the active speed test. Every number comes from a real measurement in
 * [SpeedTestEngine]; `null` means "not measured yet" (the UI shows a dash, never a 0
 * pretending to be a result).
 */
data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val phaseProgress: Float = 0f,
    val liveBitsPerSecond: Double = 0.0,
    val latencyMs: Long? = null,
    val jitterMs: Long? = null,
    val downloadBitsPerSecond: Double? = null,
    val uploadBitsPerSecond: Double? = null,
    val server: String = SpeedTestEngine.SERVER_NAME,
    val error: String? = null,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L
) {
    val isRunning: Boolean
        get() = phase == SpeedTestPhase.PING ||
            phase == SpeedTestPhase.DOWNLOAD ||
            phase == SpeedTestPhase.UPLOAD

    /** What the gauge should show right now. */
    val gaugeBitsPerSecond: Double
        get() = when (phase) {
            SpeedTestPhase.DOWNLOAD -> liveBitsPerSecond
            SpeedTestPhase.UPLOAD -> liveBitsPerSecond
            SpeedTestPhase.DONE -> downloadBitsPerSecond ?: 0.0
            else -> 0.0
        }
}

class SpeedTestViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    val history: StateFlow<List<SpeedTestResult>> = preferencesManager.speedTestResultsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var testJob: Job? = null

    fun startTest() {
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
            _state.value = SpeedTestState(
                phase = SpeedTestPhase.PING,
                startedAtMillis = System.currentTimeMillis()
            )
            try {
                val latency = SpeedTestEngine.measureLatency { sample ->
                    _state.update { it.copy(latencyMs = sample) }
                }
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.DOWNLOAD,
                        phaseProgress = 0f,
                        latencyMs = latency.minMs,
                        jitterMs = latency.jitterMs
                    )
                }

                val download = SpeedTestEngine.measureDownload { bitsPerSecond, fraction ->
                    _state.update {
                        it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction)
                    }
                }
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.UPLOAD,
                        phaseProgress = 0f,
                        liveBitsPerSecond = 0.0,
                        downloadBitsPerSecond = download.bitsPerSecond
                    )
                }

                val upload = SpeedTestEngine.measureUpload { bitsPerSecond, fraction ->
                    _state.update {
                        it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction)
                    }
                }

                preferencesManager.saveSpeedTestResult(
                    SpeedTestResult(
                        atMillis = System.currentTimeMillis(),
                        downloadBps = download.bitsPerSecond / 8.0,
                        uploadBps = upload.bitsPerSecond / 8.0,
                        latencyMs = latency.minMs,
                        jitterMs = latency.jitterMs,
                        server = SpeedTestEngine.SERVER_NAME
                    )
                )

                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.DONE,
                        phaseProgress = 1f,
                        liveBitsPerSecond = 0.0,
                        uploadBitsPerSecond = upload.bitsPerSecond,
                        finishedAtMillis = System.currentTimeMillis()
                    )
                }
            } catch (cancellation: CancellationException) {
                _state.value = SpeedTestState()
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.ERROR,
                        liveBitsPerSecond = 0.0,
                        phaseProgress = 0f,
                        error = error.message ?: "Test failed"
                    )
                }
            }
        }
    }

    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        _state.value = SpeedTestState()
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSpeedTestResults() }
    }

    override fun onCleared() {
        super.onCleared()
        testJob?.cancel()
    }
}
