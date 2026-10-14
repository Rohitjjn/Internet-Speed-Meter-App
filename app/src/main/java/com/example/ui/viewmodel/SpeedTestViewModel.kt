package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.PreferencesManager
import com.example.data.SpeedTestResult
import com.example.services.AndroidConnectivity
import com.example.services.Connectivity
import com.example.services.LiveSpeed
import com.example.services.SpeedMeterService
import com.example.services.SpeedTestException
import com.example.services.SpeedTestFailure
import com.example.services.SpeedTestRunner
import com.example.services.SpeedTestServers
import com.example.services.TestAbortSignal
import com.example.services.TestAbortedException
import com.example.services.TestDataCost
import com.example.services.TestServer
import com.example.services.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * [com.example.services.SpeedTestEngine]; `null` means "not measured yet" (the UI shows a
 * dash, never a 0 pretending to be a result).
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
    /** Set when the user must confirm the expected metered-data cost before starting. */
    val pendingConfirmationMb: Int? = null,
    /** Transport the confirmation is about (mobile, or a metered Wi-Fi / VPN). */
    val pendingConfirmationTransport: Transport = Transport.NONE,
    /** True when the system Data Saver is on while the confirmation is shown. */
    val pendingConfirmationDataSaver: Boolean = false,
    /**
     * True when the run was stopped on purpose (user, or a network change mid-test) rather
     * than by a real failure — the UI must not report "could not finish" for that.
     */
    val aborted: Boolean = false,
    /** The most recent stored result at the moment this run started (ghost needle, "vs last"). */
    val previousResult: SpeedTestResult? = null,
    /** HTTP status the server acknowledged the upload verification with (0 = none yet). */
    val uploadAckCode: Int = 0
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

    /**
     * The previous run's value for the metric the gauge is showing (download, or upload
     * during the upload phase) in bits/s — drawn as a ghost needle. `null` = no history.
     */
    val ghostBitsPerSecond: Double?
        get() {
            val previous = previousResult ?: return null
            val bytesPerSecond = when (phase) {
                SpeedTestPhase.UPLOAD -> previous.uploadBps
                else -> previous.downloadBps
            }
            return if (bytesPerSecond > 0.0) bytesPerSecond * 8.0 else null
        }
}

/**
 * Drives the opt-in speed test. The measurement itself lives in
 * [com.example.services.SpeedTestEngine]; this class owns the **rules** around it:
 *
 *  - ask before spending metered data (estimate from the real line speed),
 *  - resolve the endpoint from the stored preference and fall back only on a
 *    *reachability* failure,
 *  - watch the transport for the whole run and abort when it changes,
 *  - persist the result (20 entries) and expose the previous one for comparison.
 *
 * [connectivity], [runner] and [preferencesManager] are injectable so every rule above is
 * covered by `SpeedTestViewModelTest` without a network or a device.
 */
class SpeedTestViewModel(
    application: Application,
    private val connectivity: Connectivity,
    private val runner: SpeedTestRunner,
    private val preferencesManager: PreferencesManager,
    private val liveSpeed: () -> LiveSpeed
) : AndroidViewModel(application) {

    /** The constructor the default `ViewModelProvider` factory uses. */
    constructor(application: Application) : this(
        application = application,
        connectivity = AndroidConnectivity(application),
        runner = SpeedTestRunner.Live,
        preferencesManager = PreferencesManager(application),
        liveSpeed = { SpeedMeterService.liveSpeedFlow.value }
    )

    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    val history: StateFlow<List<SpeedTestResult>> = preferencesManager.speedTestResultsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var testJob: Job? = null
    private var activeAbort: TestAbortSignal? = null

    /**
     * Entry point of the button.
     *
     * On a **metered** connection the test asks first — it really does spend the user's
     * data (8 s download + 6 s upload at whatever the line can do). Wi-Fi/Ethernet runs
     * immediately. Nothing is ever simulated to "make the number look busy".
     */
    fun requestTest() {
        if (testJob?.isActive == true) return
        if (!connectivity.isMetered()) {
            startTest()
            return
        }
        val live = liveSpeed()
        viewModelScope.launch {
            val candidates = loadCandidates()
            _state.update {
                it.copy(
                    pendingConfirmationMb = TestDataCost.estimateMegabytes(
                        downloadBps = live.steadyDownloadBps,
                        uploadBps = live.steadyUploadBps,
                        uploadEnabled = candidates.first().supportsUpload
                    ),
                    pendingConfirmationTransport = connectivity.currentTransport(),
                    pendingConfirmationDataSaver = connectivity.isDataSaverOn()
                )
            }
        }
    }

    fun dismissConfirmation() {
        _state.update { it.copy(pendingConfirmationMb = null, pendingConfirmationDataSaver = false) }
    }

    fun confirmStart() {
        _state.update { it.copy(pendingConfirmationMb = null, pendingConfirmationDataSaver = false) }
        startTest()
    }

    /**
     * Stops a running test. The abort flag is raised **before** the job is cancelled so the
     * engine's sentinel tears the sockets down instead of waiting for a read timeout.
     */
    fun cancelTest() {
        activeAbort?.abort(getApplication<Application>().getString(R.string.test_stopped))
        testJob?.cancel()
        testJob = null
        _state.value = SpeedTestState()
    }

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSpeedTestResults() }
    }

    /**
     * The endpoint order for this run, read from DataStore **at start** (a choice saved in
     * Settings a moment ago is honoured even before any collector caught up).
     */
    private suspend fun loadCandidates(): List<TestServer> {
        val preference = runCatching { preferencesManager.testServerFlow.first() }
            .getOrDefault(SpeedTestServers.ID_AUTO)
        val customUrl = runCatching { preferencesManager.testServerUrlFlow.first() }.getOrDefault("")
        val flavour = runCatching { preferencesManager.testServerFlavourFlow.first() }
            .getOrDefault(SpeedTestServers.FLAVOUR_CLOUDFLARE)
        return SpeedTestServers.candidates(preference, customUrl, flavour)
            .ifEmpty { listOf(SpeedTestServers.CLOUDFLARE) }
    }

    fun startTest() {
        if (testJob?.isActive == true) return
        val app = getApplication<Application>()
        val abort = TestAbortSignal()
        activeAbort = abort
        val transportAtStart = connectivity.currentTransport()

        testJob = viewModelScope.launch {
            val candidates = loadCandidates()
            val previous = runCatching { preferencesManager.speedTestResultsFlow.first().firstOrNull() }
                .getOrNull()
            _state.value = SpeedTestState(
                phase = SpeedTestPhase.PING,
                startedAtMillis = System.currentTimeMillis(),
                server = candidates.first().host,
                previousResult = previous
            )

            // Watch the transport for the whole run: switching networks mid-test would mix
            // two different connections (and could silently spend mobile data).
            val transportWatcher = launch {
                while (isActive) {
                    delay(TRANSPORT_POLL_MS)
                    val now = connectivity.currentTransport()
                    if (now != transportAtStart) {
                        abort.abort(app.getString(R.string.test_error_network_changed, networkName(now)))
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
                    } catch (cancelled: CancellationException) {
                        throw cancelled
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
                                server = candidates[index + 1].host,
                                usedFallback = true
                            )
                        }
                    }
                }
                if (lastError != null) throw lastError
            } catch (cancelled: CancellationException) {
                _state.value = SpeedTestState()
                throw cancelled
            } catch (aborted: TestAbortedException) {
                if (!isActive) {
                    // The user stopped it: cancelTest() raised the flag *and* cancelled this
                    // job, and the engine happened to notice the flag first. Plain idle,
                    // not an error card that pops up after the screen already reset.
                    _state.value = SpeedTestState()
                } else {
                    _state.update {
                        it.copy(
                            phase = SpeedTestPhase.ERROR,
                            liveBitsPerSecond = 0.0,
                            phaseProgress = 0f,
                            aborted = true,
                            error = aborted.message ?: app.getString(R.string.test_stopped),
                            finishedAtMillis = System.currentTimeMillis()
                        )
                    }
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.ERROR,
                        liveBitsPerSecond = 0.0,
                        phaseProgress = 0f,
                        error = describe(error),
                        finishedAtMillis = System.currentTimeMillis()
                    )
                }
            } finally {
                transportWatcher.cancel()
                if (activeAbort === abort) activeAbort = null
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
                uploadAckCode = 0,
                error = null
            )
        }

        val latency = runner.latency(server, abort) { sample ->
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

        val download = runner.download(server, abort) { bitsPerSecond, fraction ->
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
            runner.upload(server, abort) { bitsPerSecond, fraction ->
                _state.update { it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction) }
            }
        } else {
            null
        }

        // The measurement is done; a persistence hiccup must not turn it into an "error".
        runCatching {
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
        }

        _state.update {
            it.copy(
                phase = SpeedTestPhase.DONE,
                phaseProgress = 1f,
                liveBitsPerSecond = 0.0,
                uploadBitsPerSecond = upload?.bitsPerSecond,
                uploadAckCode = upload?.serverAckCode ?: 0,
                finishedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /** Localized explanation of a failure; the engine only knows the kind + host. */
    private fun describe(error: Exception): String {
        val app = getApplication<Application>()
        if (error is SpeedTestException) {
            return when (error.failure) {
                SpeedTestFailure.UNREACHABLE ->
                    app.getString(R.string.test_error_unreachable, error.host)
                SpeedTestFailure.NO_DOWNLOAD_DATA ->
                    app.getString(R.string.test_error_no_download_data, error.host)
                SpeedTestFailure.NO_UPLOAD_ENDPOINT ->
                    app.getString(R.string.test_error_no_upload_endpoint, error.host)
                SpeedTestFailure.UPLOAD_NOT_ACKNOWLEDGED ->
                    if (error.httpCode > 0) {
                        app.getString(R.string.test_error_upload_rejected, error.host, error.httpCode)
                    } else {
                        app.getString(R.string.test_error_upload_not_acknowledged, error.host)
                    }
                SpeedTestFailure.NOTHING_SENT ->
                    app.getString(R.string.test_error_nothing_sent, error.host)
            }
        }
        val detail = error.message?.takeIf { it.isNotBlank() }
        return if (detail != null) {
            app.getString(R.string.test_error_generic, detail)
        } else {
            app.getString(R.string.test_error_unknown)
        }
    }

    private fun networkName(transport: Transport): String {
        val app = getApplication<Application>()
        return when (transport) {
            Transport.WIFI -> app.getString(R.string.network_wifi)
            Transport.MOBILE -> app.getString(R.string.network_mobile)
            Transport.ETHERNET -> app.getString(R.string.network_ethernet)
            Transport.VPN -> app.getString(R.string.network_vpn)
            Transport.NONE -> app.getString(R.string.network_offline)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeAbort?.abort("ViewModel cleared")
        testJob?.cancel()
    }

    companion object {
        /** How often the transport is re-read while a test runs. */
        const val TRANSPORT_POLL_MS = 700L
    }
}
