package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.services.NetworkMonitor
import com.example.services.SpeedTestEngine
import com.example.services.SpeedTestServers
import com.example.services.TestAbortSignal
import com.example.services.TestAbortedException
import com.example.services.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Network-gated smoke test for the active speed test.
 *
 * CI cannot run an emulator and unit tests cannot open sockets, so this is the only place
 * the real endpoint/protocol can be verified. It is deliberately tiny (one stream, 2 s) so
 * it costs a couple of MB at most, and it **skips** instead of failing when the device has
 * no connection.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SpeedTestEngineInstrumentedTest {

    private fun requireNetwork(): Transport {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = NetworkMonitor.currentTransport(context)
        assumeTrue("No active network - skipping the live endpoint smoke test", transport != Transport.NONE)
        return transport
    }

    @Test
    fun downloadMeasuresRealBytesFromTheDefaultEndpoint() = runBlocking {
        requireNetwork()
        val result = withTimeoutOrNull(30_000L) {
            SpeedTestEngine.measureDownload(
                server = SpeedTestServers.CLOUDFLARE,
                durationMs = 2_000L,
                streams = 1
            )
        }
        assertNotNull("The default endpoint did not answer within 30 s", result)
        assertTrue("No bytes arrived from the test endpoint", result!!.bytes > 0L)
        assertTrue("Throughput must be positive", result.bitsPerSecond > 0.0)
    }

    @Test
    fun latencyProbeReturnsRealRoundTrips() = runBlocking {
        requireNetwork()
        val latency = withTimeoutOrNull(30_000L) {
            SpeedTestEngine.measureLatency(SpeedTestServers.CLOUDFLARE)
        }
        assertNotNull("The latency probe did not answer within 30 s", latency)
        assertTrue(latency!!.minMs >= 0L)
        assertTrue(latency.samples > 0)
    }

    @Test
    fun uploadIsAcknowledgedByTheServerBeforeItIsMeasured() = runBlocking {
        requireNetwork()
        val result = withTimeoutOrNull(30_000L) {
            SpeedTestEngine.measureUpload(
                server = SpeedTestServers.CLOUDFLARE,
                durationMs = 1_000L,
                streams = 1
            )
        }
        assertNotNull("The upload endpoint did not answer within 30 s", result)
        // The 32 KiB verification body must be read completely and answered with a 2xx/3xx,
        // otherwise the engine throws instead of measuring how fast a proxy discards bytes.
        assertTrue("Upload must be acknowledged by the server", result!!.serverAckCode in 200..399)
        assertTrue("No bytes were sent to the test endpoint", result.bytes > 0L)
    }

    @Test
    fun anAbortedRunStopsInsteadOfReportingANumber() = runBlocking {
        requireNetwork()
        val abort = TestAbortSignal()
        abort.abort("test harness")
        var returnedNumber = false
        try {
            SpeedTestEngine.measureDownload(
                server = SpeedTestServers.CLOUDFLARE,
                durationMs = 5_000L,
                streams = 1,
                abort = abort
            )
            returnedNumber = true
        } catch (expected: TestAbortedException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
        assertTrue("An aborted run must throw, never return a number", !returnedNumber)
    }
}
