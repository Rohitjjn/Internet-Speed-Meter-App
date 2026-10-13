package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.PreferencesManager
import com.example.data.SpeedTestResult
import com.example.services.SpeedMeterService
import com.example.services.SpeedTestEngine
import com.example.services.SpeedTestServer
import com.example.services.ThroughputResult
import com.example.util.BytesFormatter
import com.example.util.SpeedFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

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
    /** Host that carried the download (and the ping). */
    val server: String = SpeedTestEngine.SERVER_NAME,
    /** Host that carried the upload — it can differ when a fallback is used. */
    val uploadServer: String? = null,
    /** True when no reachable server could take an upload. */
    val uploadUnavailable: Boolean = false,
    /** Bytes the finished test really moved (download + upload). */
    val bytesUsed: Long = 0L,
    val error: String? = null,
    val startedAtMillis: Long = 0L,
    val finishedAtMillis: Long = 0L,
    /**
     * Set when the test is about to run on a metered (mobile-data) network: the UI asks
     * for confirmation first, because an honest answer to "how much data will this use?"
     * is "more than you would like on a plan".
     */
    val awaitingMeteredConfirmation: Boolean = false
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
    private val appContext = application.applicationContext

    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    val history: StateFlow<List<SpeedTestResult>> = preferencesManager.speedTestResultsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Which public endpoint the test should prefer ("AUTO" = shipped order). */
    val serverPreference: StateFlow<String> = preferencesManager.speedTestServerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "AUTO")

    private var testJob: Job? = null
    private var transportWatchJob: Job? = null

    /** Pulled the moment the OS reports a different transport than the test started on. */
    @Volatile
    private var aborted = false

    private val abortSignal = SpeedTestEngine.Abort { aborted }

    /** Starts the test — or asks for confirmation when the active network is metered. */
    fun startTest() {
        if (testJob?.isActive == true) return
        if (!hasInternetConnection()) {
            _state.value = SpeedTestState(
                phase = SpeedTestPhase.ERROR,
                error = appContext.getString(R.string.test_no_connection)
            )
            return
        }
        if (isActiveNetworkMetered()) {
            _state.update { it.copy(awaitingMeteredConfirmation = true) }
            return
        }
        runTest()
    }

    /** Confirms the "this uses mobile data" warning and runs the test. */
    fun confirmMeteredNetwork() {
        _state.update { it.copy(awaitingMeteredConfirmation = false) }
        runTest()
    }

    fun dismissMeteredWarning() {
        _state.update { it.copy(awaitingMeteredConfirmation = false) }
    }

    private fun runTest() {
        aborted = false
        val startedTransport = SpeedMeterService.transportFlow.value
        _state.value = SpeedTestState(
            phase = SpeedTestPhase.PING,
            startedAtMillis = System.currentTimeMillis()
        )

        // Abort if the transport changes mid-test: a Wi-Fi → mobile switch would
        // otherwise bill the user's data plan for the rest of the download burst.
        transportWatchJob?.cancel()
        transportWatchJob = viewModelScope.launch {
            SpeedMeterService.transportFlow.collect { transport ->
                if (transport != startedTransport && testJob?.isActive == true) {
                    stopTest(abortedMessage())
                }
            }
        }

        testJob = viewModelScope.launch {
            try {
                val preference = runCatching { preferencesManager.speedTestServerFlow.first() }
                    .getOrDefault("AUTO")
                val server = resolveServer(preference)

                val latency = SpeedTestEngine.measureLatency(server, abortSignal) { sample ->
                    _state.update { it.copy(latencyMs = sample, server = server.host) }
                }
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.DOWNLOAD,
                        phaseProgress = 0f,
                        latencyMs = latency.minMs,
                        jitterMs = latency.jitterMs
                    )
                }

                val download = SpeedTestEngine.measureDownload(server, abort = abortSignal) { bitsPerSecond, fraction ->
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

                // Fallback servers often cannot take an upload — find one that can and
                // say which one produced the number.
                val uploadServer = if (server.supportsUpload) {
                    server
                } else {
                    SpeedTestEngine.uploadCapable(preference).firstOrNull { candidate ->
                        runCatching { SpeedTestEngine.probe(candidate) }.getOrNull() != null
                    }
                }

                if (uploadServer == null) {
                    // Nothing reachable can take an upload: report the download honestly
                    // and leave upload as "not measured" instead of inventing a value.
                    saveResult(server, null, download, null)
                    _state.update {
                        it.copy(
                            phase = SpeedTestPhase.DONE,
                            phaseProgress = 1f,
                            liveBitsPerSecond = 0.0,
                            uploadUnavailable = true,
                            bytesUsed = download.bytes,
                            finishedAtMillis = System.currentTimeMillis()
                        )
                    }
                    return@launch
                }

                val upload = SpeedTestEngine.measureUpload(uploadServer, abort = abortSignal) { bitsPerSecond, fraction ->
                    _state.update {
                        it.copy(liveBitsPerSecond = bitsPerSecond, phaseProgress = fraction)
                    }
                }

                saveResult(server, uploadServer, download, upload)
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.DONE,
                        phaseProgress = 1f,
                        liveBitsPerSecond = 0.0,
                        uploadBitsPerSecond = upload.bitsPerSecond,
                        uploadServer = uploadServer.host,
                        bytesUsed = download.bytes + upload.bytes,
                        finishedAtMillis = System.currentTimeMillis()
                    )
                }
            } catch (cancellation: CancellationException) {
                if (aborted) {
                    _state.update {
                        it.copy(
                            phase = SpeedTestPhase.ERROR,
                            liveBitsPerSecond = 0.0,
                            phaseProgress = 0f,
                            error = abortedMessage()
                        )
                    }
                } else {
                    _state.value = SpeedTestState()
                }
                throw cancellation
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = SpeedTestPhase.ERROR,
                        liveBitsPerSecond = 0.0,
                        phaseProgress = 0f,
                        error = localizeError(error)
                    )
                }
            } finally {
                transportWatchJob?.cancel()
            }
        }
    }

    /** Stops a running test — optionally surfacing why (e.g. the transport changed). */
    fun stopTest(reason: String? = null) {
        aborted = true
        testJob?.cancel()
        testJob = null
        transportWatchJob?.cancel()
        if (reason != null) {
            _state.update {
                it.copy(
                    phase = SpeedTestPhase.ERROR,
                    liveBitsPerSecond = 0.0,
                    phaseProgress = 0f,
                    error = reason
                )
            }
        } else {
            _state.value = SpeedTestState()
        }
    }

    fun cancelTest() = stopTest()

    fun clearHistory() {
        viewModelScope.launch { preferencesManager.clearSpeedTestResults() }
    }

    /**
     * Plain-text summary of the finished test — everything in it was measured, and the
     * bytes figure is the real cost of running it.
     */
    fun shareText(): String {
        val notMeasured = appContext.getString(R.string.value_not_measured)
        val lines = ArrayList<String>()
        lines += appContext.getString(R.string.share_title)
        lines += appContext.getString(
            R.string.share_download,
            state.downloadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: notMeasured
        )
        lines += appContext.getString(
            R.string.share_upload,
            state.uploadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: notMeasured
        )
        state.latencyMs?.let { lines += appContext.getString(R.string.share_ping, it) }
        state.jitterMs?.let { lines += appContext.getString(R.string.share_jitter, it) }
        lines += appContext.getString(R.string.share_download_server, state.server)
        if (state.uploadServer != null && state.uploadServer != state.server) {
            lines += appContext.getString(R.string.share_upload_server, state.uploadServer)
        }
        if (state.bytesUsed > 0L) {
            lines += appContext.getString(R.string.share_data_used, BytesFormatter.format(state.bytesUsed))
        }
        return lines.joinToString("
")
    }

    /**
     * The engine is Context-free, so it throws marker messages for the two errors the app
     * itself raises; everything else is an endpoint/server message shown as-is.
     */
    private fun localizeError(error: Exception): String = when (error.message) {
        SpeedTestEngine.ABORT_MESSAGE -> abortedMessage()
        SpeedTestEngine.NO_SERVER_MESSAGE -> appContext.getString(R.string.test_no_server)
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.test_error_body)
    }

    private fun abortedMessage(): String = appContext.getString(R.string.test_aborted)

    private suspend fun resolveServer(preference: String): SpeedTestServer {
        var lastError: Exception? = null
        for (candidate in SpeedTestEngine.candidates(preference)) {
            val rtt = runCatching { SpeedTestEngine.probe(candidate) }
            if (rtt.isSuccess) return candidate
            lastError = rtt.exceptionOrNull() as? Exception ?: lastError
        }
        throw IOException(lastError?.message ?: SpeedTestEngine.NO_SERVER_MESSAGE)
    }

    private suspend fun saveResult(
        server: SpeedTestServer,
        uploadServer: SpeedTestServer?,
        download: ThroughputResult,
        upload: ThroughputResult?
    ) {
        runCatching {
            preferencesManager.saveSpeedTestResult(
                SpeedTestResult(
                    atMillis = System.currentTimeMillis(),
                    downloadBps = download.bitsPerSecond / 8.0,
                    uploadBps = (upload?.bitsPerSecond ?: 0.0) / 8.0,
                    latencyMs = _state.value.latencyMs ?: 0L,
                    jitterMs = _state.value.jitterMs ?: 0L,
                    server = server.host,
                    uploadServer = uploadServer?.host ?: server.host,
                    bytesUsed = download.bytes + (upload?.bytes ?: 0L)
                )
            )
        }
    }

    private fun connectivityManager(): ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Real connectivity check — never blocks a test on a dead radio. */
    private fun hasInternetConnection(): Boolean {
        return try {
            val cm = connectivityManager() ?: return false
            val network = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            // Without a definitive answer we let the test run and fail honestly.
            true
        }
    }

    /**
     * True when the active network is metered (typically mobile data). Used only to warn
     * the user *before* the test — the app never blocks or throttles anything.
     */
    private fun isActiveNetworkMetered(): Boolean {
        return try {
            connectivityManager()?.isActiveNetworkMetered == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        testJob?.cancel()
        transportWatchJob?.cancel()
    }
}
