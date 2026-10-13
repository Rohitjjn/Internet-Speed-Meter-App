package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesManager
import com.example.data.SpeedTestResult
import com.example.services.NetworkMonitor
import com.example.services.SpeedMeterService
import com.example.services.SpeedTestEngine
import com.example.services.SpeedTestServers
import com.example.services.TestAbortSignal
import com.example.services.TestAbortedException
import com.example.services.TestDataCost
import com.example.services.TestServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    /** Host the numbers above actually came from — never a hard-coded guess. */
    val server: String = SpeedTestServers.CLOUDFLARE.host,
    val error: String? = null,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    /** True when a second endpoint had to be used because the first one was unreachable. */
    val usedFallback: Boolean = false,
    /** Set when the user must confirm the expected mobile-data cost before starting. */
    val pendingConfirmationMb: Int? = null,
    /**
     * True when the run was stopped on purpose (user, or a network change mid-test) rather
     * than by a real failure — the UI must not report "could not finish" for that.
     */
    val aborted: Boolean = false
) {
    val isRunning: Boolean
        get() = phase == SpeedTestPhase.PING ||
            phase == SpeedTestPhase.DOWNLOAD ||
            phase == SpeedTestPhase.UPLOAD

    /** What the gauge needle should follow right now. */
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
    private var transportWatcher: Job? = null

    // Mirror of the stored server preferences (a test can start before the first
    // collection completes; the defaults are exactly what a fresh install uses anyway).
    private var serverPreference: String = SpeedTestServers.ID_AUTO
    private var customUrl: String = ""
    private var customFlavour: String = SpeedTestServers.FLAVOUR_CLOUDFLARE

    init {
        viewModelScope.launch {
            preferencesManager.testServerFlow.collect { serverPreference = it }
        }
        viewModelScope.launch {
            preferencesManager.testServerUrlFlow.collect { customUrl = it }
        }
        viewModelScope.launch {
            preferencesManager.testServerFlavourFlow.collect { customFlavour = it }
        }
    }

    /**
     * Entry point of the button.
     *
     * On a **metered** connection the test asks first — it really does spend the user's
     * data (8 s download + 6 s upload at whatever the line can do). Wi-Fi/Ethernet runs
     * immediately. Nothing is ever simulated to "make the number look busy".
     */
    fun requestTest() {
        if (testJob?.isActive == true) return
        val context = getApplication<Application>()
        if (NetworkMonitor.isMetered(context)) {
            val live = SpeedMeterService.liveSpeedFlow.value
            _state.update {
                it.copy(
                    pendingConfirmationMb = TestDataCost.estimateMegabytes(
                        downloadBps = live.steadyDownloadBps,
                        uploadBps = live.steadyUploadBps,
                        uploadEnabled = candidates().firstOrNull()?.supportsUpload ?: true
                    )
                )
            }
            return
        }
        startTest()
    }

    fun dismissConfirmation() {
        _state.update { it.copy(pendingConfirmationMb = null) }
    }

    fun confirmStart() {
        _state.update { it.copy(pendingConfirmationMb = null) }
        startTest()
    }

    fun cancelTest() {
        testJob?.cancel()
        testJob = null
        transportWatcher?.cancel()
        transportWatcher = null
        _state.value = SpeedTestState()
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSpeedTestResults() }
    }

    private fun candidates(): List<TestServer> =
        SpeedTestServers.candidates(serverPreference, customUrl, customFlavour)
            .ifEmpty { listOf(SpeedTestServers.CLOUDFLARE) }

    fun startTest() {
        if (testJob?.isActive == true) return
        val context = getApplication<Application>()
        val abort = TestAbortSignal()
        val transportAtStart = NetworkMonitor.currentTransport(context)
        val candidates = candidates()

        testJob = viewModelScope.launch {
            _state.value = SpeedTestState(
                phase = SpeedTestPhase.PING,
                startedAtMillis = System.currentTimeMillis(),
                server = candidates.first().host
            )

            // Watch the transport for the whole run: switching networks mid-test would mix
            // two different connections (and could silently spend mobile data).
            transportWatcher = launch {
                while (isActive) {
                    delay(700L)
                    val now = NetworkMonitor.currentTransport(context)
                    if (now != transportAtStart) {
                        abort.abort(
                            "Network changed to ${NetworkMonitor.labelOf(now)} mid-test — the " +
                                "test was stopped so its result cannot mix two connections."
                        )
                        break
                    }
                }
            }

            try {
                var lastError: Exception? = null
                for ((index, candidate) in candidates.withIndex()) {
                    val isLast = index == candidates.lastIndex
                    try {
                        runOneTest(candidate, abort, usedFallback = index > 0)
                        lastError = null
                        break
                    } catch (aborted: TestAbortedException) {
                        throw aborted
                    } catch (error: Exception) {
                        lastError = error
                        // Only a *reachability* failure falls through to the next endpoint
                        // (the latency probe is the cheapest proof that it cannot be reached);
                        // retrying after a download failure would just spend more data.
                        val reachabilityFailed = _state.value.phase == SpeedTestPhase.PING
                        if (isLast || !reachabilityFailed) throw error
                        _state.update {
                            it.copy(
                                phase = SpeedTestPhase.PING,
                                phaseProgress = 0f,
                                latencyMs = null,
                                jitterMs = null,
                                liveBitsPerSecond = 0.0,
                                error = null,
                                server = candidates[index + 1].host,
                                usedFallback = true
                            )
                        }
                    }
                }
                lastError?.let { throw it }
            } catch (aborted: TestAbortedException) {
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.ERROR,
                        liveBitsPerSecond = 0.0,
                        phaseProgress = 0f,
                        error = aborted.message ?: "Test stopped",
                        aborted = true,
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
                        error = error.message ?: "Test failed",
                        finishedAtMillis = System.currentTimeMillis()
                    )
                }
            } finally {
                transportWatcher?.cancel()
                transportWatcher = null
            }
        }
    }

    /** One full measurement against one endpoint. Throws when the endpoint cannot serve it. */
    private suspend fun runOneTest(server: TestServer, abort: TestAbortSignal, usedFallback: Boolean) {
        _state.update {
            it.copy(
                phase = SpeedTestPhase.PING,
                phaseProgress = 0f,
                liveBitsPerSecond = 0.0,
                downloadBitsPerSecond = null,
                uploadBitsPerSecond = null,
                latencyMs = null,
                jitterMs = null,
                server = server.host,
                usedFallback = usedFallback,
                error = null
            )
        }

        val latency = SpeedTestEngine.measureLatency(server, abort) { sample ->
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

        val download = SpeedTestEngine.measureDownload(server, abort = abort) { bitsPerSecond, fraction ->
            _state.update { it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction) }
        }
        _state.update {
            it.copy(
                phase = SpeedTestPhase.UPLOAD,
                phaseProgress = 0f,
                liveBitsPerSecond = 0.0,
                downloadBitsPerSecond = download.bitsPerSecond
            )
        }

        val upload = if (server.supportsUpload) {
            SpeedTestEngine.measureUpload(server, abort = abort) { bitsPerSecond, fraction ->
                _state.update { it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction) }
            }
        } else {
            null
        }

        preferencesManager.saveSpeedTestResult(
            SpeedTestResult(
                atMillis = System.currentTimeMillis(),
                downloadBps = download.bitsPerSecond / 8.0,
                uploadBps = (upload?.bitsPerSecond ?: 0.0) / 8.0,
                latencyMs = latency.minMs,
                jitterMs = latency.jitterMs,
                server = server.host
            )
        )

        _state.update {
            it.copy(
                phase = SpeedTestPhase.DONE,
                phaseProgress = 1f,
                liveBitsPerSecond = 0.0,
                uploadBitsPerSecond = upload?.bitsPerSecond,
                finishedAtMillis = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        testJob?.cancel()
        transportWatcher?.cancel()
    }
}
