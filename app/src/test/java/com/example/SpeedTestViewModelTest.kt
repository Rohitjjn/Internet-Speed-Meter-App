package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.data.SpeedTestResult
import com.example.services.Connectivity
import com.example.services.LatencyResult
import com.example.services.LiveSpeed
import com.example.services.SpeedTestException
import com.example.services.SpeedTestFailure
import com.example.services.SpeedTestRunner
import com.example.services.SpeedTestServers
import com.example.services.TestAbortSignal
import com.example.services.TestServer
import com.example.services.ThroughputResult
import com.example.services.Transport
import com.example.ui.viewmodel.SpeedTestPhase
import com.example.ui.viewmodel.SpeedTestState
import com.example.ui.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The *rules* around the speed test, with a fake connectivity probe and a fake runner so
 * nothing touches the network:
 *
 *  - a metered connection asks before spending data, an unmetered one runs at once;
 *  - AUTO falls back to the second endpoint only when the first cannot be *reached*;
 *  - an explicit endpoint choice never falls back;
 *  - a transport change mid-run aborts without producing a number;
 *  - the previous stored result is exposed for the ghost needle / "vs last test" chip;
 *  - STOP returns to idle and the engine sees the abort flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpeedTestViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var isolated: TestPreferences
    private lateinit var preferences: PreferencesManager
    private lateinit var connectivity: FakeConnectivity
    private lateinit var runner: FakeRunner

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        // Own DataStore file per test: AUTO endpoint and an empty history are the real
        // defaults here, not something restored from a shared singleton.
        isolated = TestPreferences(application)
        preferences = isolated.manager
        connectivity = FakeConnectivity()
        runner = FakeRunner()
    }

    @After
    fun tearDown() {
        isolated.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): SpeedTestViewModel = SpeedTestViewModel(
        application = application,
        connectivity = connectivity,
        runner = runner,
        preferencesManager = preferences,
        liveSpeed = { LiveSpeed(steadyDownloadBps = 2_500_000L, steadyUploadBps = 800_000L) }
    )

    private suspend fun SpeedTestViewModel.awaitPhase(vararg phases: SpeedTestPhase): SpeedTestState =
        state.first { it.phase in phases }

    @Test
    fun repeatedRequestsKeepOneConfirmationAndOneEstimate() = runTest(dispatcher) {
        connectivity.metered = true
        var snapshots = 0
        val vm = SpeedTestViewModel(application, connectivity, runner, preferences) {
            snapshots++
            LiveSpeed(steadyDownloadBps = 2_500_000L)
        }
        vm.requestTest()
        vm.requestTest() // lookup is queued, dialog not yet visible
        val asked = vm.state.first { it.pendingConfirmationMb != null }
        vm.requestTest() // already displayed
        assertEquals(1, snapshots)
        assertEquals(asked.pendingConfirmationMb, vm.state.value.pendingConfirmationMb)
        assertEquals(0, runner.latencyCalls)
        vm.confirmStart()
        vm.confirmStart() // a stale second confirmation is harmless
        vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)
        assertEquals(1, runner.latencyCalls)
    }

    @Test
    fun dismissingQueuedLookupDoesNotResurrectConfirmation() = runTest(dispatcher) {
        connectivity.metered = true
        val vm = viewModel()
        vm.requestTest()
        vm.dismissConfirmation()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingConfirmationMb)
        vm.confirmStart() // no visible confirmation means no permission to run
        assertEquals(0, runner.latencyCalls)
        vm.requestTest()
        vm.state.first { it.pendingConfirmationMb != null }
        vm.cancelTest()
        assertNull(vm.state.value.pendingConfirmationMb)
    }

    @Test
    fun meteredConnectionAsksBeforeSpendingData() = runTest(dispatcher) {
        connectivity.metered = true
        connectivity.transport = Transport.MOBILE
        val vm = viewModel()

        vm.requestTest()
        val asked = vm.state.first { it.pendingConfirmationMb != null }

        assertEquals(SpeedTestPhase.IDLE, asked.phase)
        assertTrue("estimate must be a real positive figure", asked.pendingConfirmationMb!! > 0)
        assertEquals(Transport.MOBILE, asked.pendingConfirmationTransport)
        assertEquals("nothing may be measured before the user agrees", 0, runner.latencyCalls)

        vm.dismissConfirmation()
        assertNull(vm.state.value.pendingConfirmationMb)
        assertEquals(SpeedTestPhase.IDLE, vm.state.value.phase)
        assertEquals(0, runner.latencyCalls)
    }

    @Test
    fun confirmingTheMeteredDialogRunsTheTest() = runTest(dispatcher) {
        connectivity.metered = true
        connectivity.transport = Transport.MOBILE
        connectivity.dataSaver = true
        val vm = viewModel()

        vm.requestTest()
        val asked = vm.state.first { it.pendingConfirmationMb != null }
        assertTrue(asked.pendingConfirmationDataSaver)

        vm.confirmStart()
        val done = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.DONE, done.phase)
        assertNull(done.pendingConfirmationMb)
        assertEquals(SpeedTestServers.CLOUDFLARE.host, done.server)
        assertFalse(done.usedFallback)
        assertEquals(12L, done.latencyMs)
        assertEquals(3L, done.jitterMs)
        assertEquals(80_000_000.0, done.downloadBitsPerSecond!!, 0.0)
        assertEquals(20_000_000.0, done.uploadBitsPerSecond!!, 0.0)
        assertEquals("the server's acknowledgement is part of the result", 200, done.uploadAckCode)
    }

    @Test
    fun unmeteredConnectionRunsImmediately() = runTest(dispatcher) {
        connectivity.metered = false
        connectivity.transport = Transport.WIFI
        val vm = viewModel()

        vm.requestTest()
        val done = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.DONE, done.phase)
        assertNull("Wi-Fi must not show the data-cost dialog", done.pendingConfirmationMb)
        assertEquals(1, runner.latencyCalls)
    }

    @Test
    fun autoFallsBackWhenTheFirstEndpointCannotBeReached() = runTest(dispatcher) {
        runner.unreachableHosts += SpeedTestServers.CLOUDFLARE.host
        val vm = viewModel()

        vm.startTest()
        val done = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.DONE, done.phase)
        assertTrue(done.usedFallback)
        assertEquals("the result must say which host it came from", SpeedTestServers.LIBRESPEED.host, done.server)
        assertEquals(listOf(SpeedTestServers.CLOUDFLARE.host, SpeedTestServers.LIBRESPEED.host), runner.latencyHosts)
    }

    @Test
    fun aDownloadFailureDoesNotFallBackToAnotherEndpoint() = runTest(dispatcher) {
        runner.downloadFailsFor += SpeedTestServers.CLOUDFLARE.host
        val vm = viewModel()

        vm.startTest()
        val result = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.ERROR, result.phase)
        assertFalse("a real failure is not an abort", result.aborted)
        assertEquals(SpeedTestServers.CLOUDFLARE.host, result.server)
        assertFalse(result.usedFallback)
        assertNull("no number may be reported for a failed phase", result.downloadBitsPerSecond)
        assertEquals("retrying after a download failure would just spend more data", 1, runner.latencyCalls)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains(SpeedTestServers.CLOUDFLARE.host))
    }

    @Test
    fun anExplicitEndpointChoiceNeverFallsBack() = runTest(dispatcher) {
        preferences.saveTestServer(SpeedTestServers.ID_LIBRESPEED, "", SpeedTestServers.FLAVOUR_CLOUDFLARE)
        runner.unreachableHosts += SpeedTestServers.LIBRESPEED.host
        val vm = viewModel()

        vm.startTest()
        val result = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.ERROR, result.phase)
        assertEquals(SpeedTestServers.LIBRESPEED.host, result.server)
        assertFalse(result.usedFallback)
        assertEquals(listOf(SpeedTestServers.LIBRESPEED.host), runner.latencyHosts)
    }

    @Test
    fun aTransportChangeMidRunAbortsWithoutANumber() = runTest(dispatcher) {
        connectivity.transport = Transport.WIFI
        // The download "hangs" until the engine is told to abort, and flips the transport
        // the moment it starts — exactly what a Wi-Fi drop during a test looks like.
        runner.onDownloadStart = { connectivity.transport = Transport.MOBILE }
        runner.downloadWaitsForAbort = true
        val vm = viewModel()

        vm.startTest()
        val result = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.ERROR, result.phase)
        assertTrue("a network change is an abort, not a failure", result.aborted)
        assertNull(result.downloadBitsPerSecond)
        assertNull(result.uploadBitsPerSecond)
        assertTrue(runner.abortSeen)
        assertTrue(result.error!!.contains(application.getString(R.string.network_mobile)))
    }

    @Test
    fun thePreviousStoredResultIsExposedForComparison() = runTest(dispatcher) {
        preferences.saveSpeedTestResult(
            SpeedTestResult(
                atMillis = 1_000L,
                downloadBps = 5_000_000.0,
                uploadBps = 1_000_000.0,
                latencyMs = 20L,
                jitterMs = 4L,
                server = "earlier.example"
            )
        )
        val vm = viewModel()

        vm.startTest()
        val done = vm.awaitPhase(SpeedTestPhase.DONE, SpeedTestPhase.ERROR)

        assertEquals(SpeedTestPhase.DONE, done.phase)
        assertEquals("earlier.example", done.previousResult?.server)
        // Ghost needle = previous download in bits/s while the gauge shows download.
        assertEquals(40_000_000.0, done.ghostBitsPerSecond!!, 0.0)
        // And the new run was appended to the history, newest first.
        val history = preferences.speedTestResultsFlow.first()
        assertEquals(2, history.size)
        assertEquals(SpeedTestServers.CLOUDFLARE.host, history.first().server)
    }

    @Test
    fun stopReturnsToIdleAndTheEngineSeesTheAbortFlag() = runTest(dispatcher) {
        runner.downloadWaitsForAbort = true
        val vm = viewModel()

        vm.startTest()
        vm.awaitPhase(SpeedTestPhase.DOWNLOAD)
        vm.cancelTest()
        // cancelTest() resets the state synchronously; the cancelled job itself unwinds
        // on the test scheduler — run it so the fake's finally block gets to observe.
        advanceUntilIdle()
        vm.state.first { it.phase == SpeedTestPhase.IDLE }

        assertEquals(SpeedTestPhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.error)
        assertTrue("the abort flag must be raised before the job is cancelled", runner.abortSeen)
    }

    // ------------------------------------------------------------------ fakes

    private class FakeConnectivity : Connectivity {
        @Volatile var transport: Transport = Transport.WIFI
        @Volatile var metered: Boolean = false
        @Volatile var dataSaver: Boolean = false

        override fun currentTransport(): Transport = transport
        override fun isMetered(): Boolean = metered
        override fun isDataSaverOn(): Boolean = dataSaver
    }

    private class FakeRunner : SpeedTestRunner {
        val unreachableHosts = mutableSetOf<String>()
        val downloadFailsFor = mutableSetOf<String>()
        var downloadWaitsForAbort = false
        var onDownloadStart: () -> Unit = {}

        val latencyHosts = mutableListOf<String>()
        val latencyCalls: Int get() = latencyHosts.size
        @Volatile var abortSeen = false

        override suspend fun latency(
            server: TestServer,
            abort: TestAbortSignal,
            onSample: (Long) -> Unit
        ): LatencyResult {
            latencyHosts += server.host
            if (server.host in unreachableHosts) {
                throw SpeedTestException(
                    SpeedTestFailure.UNREACHABLE, server.host,
                    message = "No response from ${server.host}"
                )
            }
            onSample(14L)
            onSample(12L)
            return LatencyResult(minMs = 12L, averageMs = 13L, jitterMs = 3L, samples = 2)
        }

        override suspend fun download(
            server: TestServer,
            abort: TestAbortSignal,
            onProgress: (Double, Float) -> Unit
        ): ThroughputResult {
            onDownloadStart()
            if (server.host in downloadFailsFor) {
                throw SpeedTestException(
                    SpeedTestFailure.NO_DOWNLOAD_DATA, server.host,
                    message = "${server.host} sent no data"
                )
            }
            if (downloadWaitsForAbort) {
                try {
                    while (!abort.isAborted) delay(50L)
                } finally {
                    // Survives a job cancellation too: what matters is that the flag was
                    // raised *before* the coroutine was torn down.
                    abortSeen = abort.isAborted
                }
                abort.throwIfAborted()
            }
            onProgress(40_000_000.0, 0.5f)
            return ThroughputResult(bitsPerSecond = 80_000_000.0, bytes = 80_000_000L, durationMs = 8_000L, streams = 4)
        }

        override suspend fun upload(
            server: TestServer,
            abort: TestAbortSignal,
            onProgress: (Double, Float) -> Unit
        ): ThroughputResult {
            abort.throwIfAborted()
            onProgress(10_000_000.0, 0.5f)
            return ThroughputResult(
                bitsPerSecond = 20_000_000.0,
                bytes = 15_000_000L,
                durationMs = 6_000L,
                streams = 3,
                serverAckCode = 200
            )
        }
    }
}
