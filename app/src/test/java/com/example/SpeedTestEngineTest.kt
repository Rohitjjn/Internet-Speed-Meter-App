package com.example

import com.example.data.SpeedTestResult
import com.example.services.SpeedTestEngine
import com.example.util.GaugeScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the **active** speed test: server failover, the honest reporting of what
 * could and could not be measured, and the gauge scale.
 *
 * Nothing here touches the network — the socket-level measurement itself is covered by
 * the network-gated instrumented test.
 */
class SpeedTestEngineTest {

    // ------------------------------------------------------------- servers

    @Test
    fun autoKeepsTheShippedServerOrder() {
        val candidates = SpeedTestEngine.candidates("AUTO")
        assertEquals(SpeedTestEngine.servers.size, candidates.size)
        assertEquals(SpeedTestEngine.CLOUDFLARE.id, candidates.first().id)
    }

    @Test
    fun anExplicitPreferenceIsTriedFirstButNeverAlone() {
        val candidates = SpeedTestEngine.candidates("hetzner") // case-insensitive on purpose
        assertEquals(SpeedTestEngine.HETZNER.id, candidates.first().id)
        assertTrue(candidates.any { it.id == SpeedTestEngine.CLOUDFLARE.id })
        assertTrue("a pinned server that is dead must still fail over", candidates.size > 1)
    }

    @Test
    fun anUnknownPreferenceFallsBackToTheShippedOrder() {
        assertEquals(SpeedTestEngine.servers, SpeedTestEngine.candidates("does-not-exist"))
    }

    @Test
    fun onlyUploadCapableServersAreOfferedForTheUploadPhase() {
        val upload = SpeedTestEngine.uploadCapable("AUTO")
        assertTrue(upload.isNotEmpty())
        assertTrue(upload.all { it.supportsUpload })
        assertEquals(SpeedTestEngine.CLOUDFLARE.id, upload.first().id)
    }

    @Test
    fun downloadUrlsSubstituteTheRequestedSize() {
        assertEquals(
            "https://speed.cloudflare.com/__down?bytes=20480000",
            SpeedTestEngine.CLOUDFLARE.downloadUrl(20_480_000L)
        )
        assertTrue(SpeedTestEngine.OVH.downloadUrl(1_000L).startsWith("https://"))
    }

    @Test
    fun serversWithoutAnUploadEndpointSaySo() {
        assertFalse(SpeedTestEngine.HETZNER.supportsUpload)
        assertFalse(SpeedTestEngine.OVH.supportsUpload)
        assertTrue(SpeedTestEngine.CLOUDFLARE.supportsUpload)
    }

    @Test
    fun serverLookupIsCaseInsensitiveAndDefensive() {
        assertEquals(SpeedTestEngine.CLOUDFLARE, SpeedTestEngine.serverById("cloudflare"))
        assertNull(SpeedTestEngine.serverById("nope"))
    }

    @Test
    fun everyServerHasAReachableLookingHttpsEndpoint() {
        SpeedTestEngine.servers.forEach { server ->
            assertTrue("${server.id} latency url", server.latencyUrl.startsWith("https://"))
            assertTrue("${server.id} download url", server.downloadUrl(1_000L).startsWith("https://"))
            assertTrue("${server.id} host", server.host.isNotBlank())
            assertTrue("${server.id} name", server.name.isNotBlank())
        }
    }

    // --------------------------------------------------------- gauge scale

    @Test
    fun gaugeScaleStartsAtTenMegabits() {
        assertEquals(10_000_000.0, GaugeScale.maxBps(0.0), 0.0)
        assertEquals(10_000_000.0, GaugeScale.maxBps(1_000.0), 0.0)
    }

    @Test
    fun gaugeScalePicksTheFirstStepThatFitsWithHeadroom() {
        assertEquals(100_000_000.0, GaugeScale.maxBps(50_000_000.0), 0.0)
        assertEquals(1_000_000_000.0, GaugeScale.maxBps(800_000_000.0), 0.0)
    }

    @Test
    fun gaugeScaleNeverExceedsItsLadder() {
        assertEquals(GaugeScale.maxBps(1e12), GaugeScale.maxBps(1e15), 0.0)
        assertTrue(GaugeScale.maxBps(Double.NaN) > 0.0)
    }

    // ------------------------------------------------ result persistence

    @Test
    fun resultsRememberWhichServerProducedEachNumber() {
        val result = SpeedTestResult(
            atMillis = 1_700_000_000_000L,
            downloadBps = 12_500_000.0,
            uploadBps = 1_250_000.0,
            latencyMs = 24L,
            jitterMs = 6L,
            server = "nbg1-speed.hetzner.com",
            uploadServer = "speed.cloudflare.com",
            bytesUsed = 42_000_000L
        )
        val decoded = SpeedTestResult.decode(SpeedTestResult.encode(listOf(result))).single()
        assertEquals("nbg1-speed.hetzner.com", decoded.server)
        assertEquals("speed.cloudflare.com", decoded.uploadServer)
        assertEquals(42_000_000L, decoded.bytesUsed)
    }

    @Test
    fun legacyResultLinesStillDecodeWithSaneDefaults() {
        // Six fields = an entry written before the upload server / bytes were recorded.
        val legacy = "1700000000000|12500000|1250000|24|6|speed.cloudflare.com"
        val decoded = SpeedTestResult.decode(legacy).single()
        assertEquals("speed.cloudflare.com", decoded.uploadServer)
        assertEquals(0L, decoded.bytesUsed)
    }

    @Test
    fun aServerNameCanNeverBreakTheEncoding() {
        val sneaky = SpeedTestResult(
            atMillis = 1L,
            downloadBps = 1.0,
            uploadBps = 1.0,
            latencyMs = 1L,
            jitterMs = 1L,
            server = "bad|server|name",
            uploadServer = "worse|name",
            bytesUsed = 5L
        )
        val decoded = SpeedTestResult.decode(SpeedTestResult.encode(listOf(sneaky))).single()
        assertEquals("bad  server  name", decoded.server)
        assertEquals("worse name", decoded.uploadServer)
        assertEquals(5L, decoded.bytesUsed)
    }
}
