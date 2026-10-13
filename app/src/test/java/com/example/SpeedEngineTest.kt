package com.example

import com.example.services.SpeedEngine
import com.example.services.SpeedSample
import com.example.services.TransferBytes
import com.example.services.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement core, tested without any Android dependency.
 *
 * These tests exist because of a real reported bug: the meter showed the *same* number
 * for download and upload, and stuttering duplicate values. Every scenario that could
 * produce a mirrored or invented value is pinned down here.
 */
class SpeedEngineTest {

    private fun engine() = SpeedEngine(
        publishIntervalMs = 1_000L,
        maxValidWindowMs = 3_000L,
        smoothing = 0.5,
        averageWindowMs = 60_000L,
        historySize = 60
    )

    /**
     * Feeds a 500 ms read cadence for [seconds] with the given per-second rates and
     * returns the published snapshots.
     */
    private fun run(
        engine: SpeedEngine,
        seconds: Int,
        rxPerSecond: Long,
        txPerSecond: Long,
        startMs: Long = 0L,
        transport: Transport = Transport.WIFI
    ): List<com.example.services.LiveSpeed> {
        engine.setTransport(transport)
        var rx = 1_000_000L
        var tx = 500_000L
        engine.seed(SpeedSample(rx, tx, startMs))
        val published = ArrayList<com.example.services.LiveSpeed>()
        var now = startMs
        for (step in 1..(seconds * 2)) {
            now = startMs + step * 500L
            rx += rxPerSecond / 2
            tx += txPerSecond / 2
            engine.onSample(SpeedSample(rx, tx, now))?.let { published += it }
        }
        return published
    }

    // ------------------------------------------------------------------ directions

    @Test
    fun downloadAndUploadAreNeverMirrored() {
        val engine = engine()
        // 100 KB/s down, 10 KB/s up — an asymmetric, download-heavy connection.
        val published = run(engine, seconds = 4, rxPerSecond = 100_000L, txPerSecond = 10_000L)

        assertTrue(published.size >= 3)
        published.forEach { live ->
            assertEquals("download", 100_000L, live.downloadBps)
            assertEquals("upload", 10_000L, live.uploadBps)
            assertFalse(live.downloadBps == live.uploadBps)
        }
    }

    @Test
    fun uploadHeavyTrafficOnlyMovesTheUploadNumber() {
        val engine = engine()
        val published = run(engine, seconds = 3, rxPerSecond = 0L, txPerSecond = 250_000L)

        assertTrue(published.isNotEmpty())
        val last = published.last()
        assertEquals(0L, last.downloadBps)
        assertEquals(250_000L, last.uploadBps)
    }

    @Test
    fun zeroTrafficPublishesZeroWithoutInventingData() {
        val engine = engine()
        val published = run(engine, seconds = 3, rxPerSecond = 0L, txPerSecond = 0L)

        assertTrue(published.isNotEmpty())
        assertTrue(published.all { it.downloadBps == 0L && it.uploadBps == 0L })
        assertFalse(published.last().paused)
    }

    // ------------------------------------------------------------------ window guard

    @Test
    fun rateUsesTheRealMeasuredWindowNotAnAssumedSecond() {
        val engine = engine()
        engine.setTransport(Transport.WIFI)
        engine.seed(SpeedSample(0L, 0L, 0L))

        // 1.5 s window carrying 150 000 bytes → 100 000 B/s, not 150 000.
        val published = engine.onSample(SpeedSample(150_000L, 0L, 1_500L))
        assertNotNull(published)
        assertEquals(100_000L, published!!.downloadBps)
        assertEquals(1_500L, published.windowMs)
    }

    @Test
    fun suspendedProcessPublishesZeroInsteadOfAnAveragedFake() {
        val engine = engine()
        engine.setTransport(Transport.WIFI)
        engine.seed(SpeedSample(0L, 0L, 0L))

        // The process was frozen for 20 s while 2 MB crossed the wire.
        val published = engine.onSample(SpeedSample(2_000_000L, 500_000L, 20_000L))
        assertNotNull(published)
        assertEquals(0L, published!!.downloadBps)
        assertEquals(0L, published.uploadBps)
        assertTrue(published.paused)
        // …but the real bytes are still accounted for (they really were transferred).
        assertEquals(2_000_000L, published.sessionRxBytes)
        assertEquals(500_000L, published.sessionTxBytes)
    }

    @Test
    fun pausedWindowDoesNotPolluteHistoryOrAverage() {
        val engine = engine()
        engine.setTransport(Transport.MOBILE)
        engine.seed(SpeedSample(0L, 0L, 0L))
        engine.onSample(SpeedSample(50_000L, 0L, 1_000L))
        engine.onSample(SpeedSample(9_000_000L, 0L, 40_000L)) // frozen window

        val snapshot = engine.snapshot()
        assertEquals(1, snapshot.downloadHistory.size)
        assertEquals(50_000L, snapshot.averageDownloadBps)
    }

    // ------------------------------------------------------------------ counter resets

    @Test
    fun counterGoingBackwardsNeverProducesASpike() {
        val engine = engine()
        engine.setTransport(Transport.WIFI)
        engine.seed(SpeedSample(9_000_000L, 1_000_000L, 0L))

        // Interface reset: totals dropped. Must not publish anything at all.
        val published = engine.onSample(SpeedSample(1_000L, 2_000L, 1_000L))
        assertNull(published)

        // The engine re-anchored, so the next window is measured normally again.
        val next = engine.onSample(SpeedSample(101_000L, 2_000L, 2_000L))
        assertNotNull(next)
        assertEquals(100_000L, next!!.downloadBps)
    }

    @Test
    fun unsupportedCountersAreNotTreatedAsZeroBytes() {
        val engine = engine()
        // First reading: TrafficStats reports the upload counter as UNSUPPORTED.
        engine.seed(SpeedSample(1_000L, 0L, 0L, rxSupported = true, txSupported = false))
        val published = engine.onSample(
            SpeedSample(101_000L, 0L, 1_000L, rxSupported = true, txSupported = false)
        )
        assertNotNull(published)
        assertEquals(100_000L, published!!.downloadBps)
        assertEquals(0L, published.uploadBps)
    }

    // ------------------------------------------------------------------ session stats

    @Test
    fun sessionAndPeakTrackTheRealMeasurements() {
        val engine = engine()
        val published = run(engine, seconds = 5, rxPerSecond = 80_000L, txPerSecond = 8_000L)
        val last = published.last()

        assertEquals(80_000L, last.peakDownloadBps)
        assertEquals(8_000L, last.peakUploadBps)
        assertEquals(80_000L, last.averageDownloadBps)
        assertEquals(8_000L, last.averageUploadBps)
        // 5 windows × 1 s of traffic.
        assertEquals(400_000L, last.sessionRxBytes)
        assertEquals(40_000L, last.sessionTxBytes)
        assertTrue(last.sessionBytes > 0L)
    }

    @Test
    fun perTransportAttributionSplitsTheBytes() {
        val engine = engine()
        run(engine, seconds = 2, rxPerSecond = 100_000L, txPerSecond = 0L, transport = Transport.WIFI)
        run(
            engine,
            seconds = 2,
            rxPerSecond = 50_000L,
            txPerSecond = 0L,
            startMs = 2_000L,
            transport = Transport.MOBILE
        )

        val totals = engine.attributedTotals()
        assertEquals(200_000L, totals[Transport.WIFI]?.rx)
        assertEquals(100_000L, totals[Transport.MOBILE]?.rx)
        assertEquals(null, totals[Transport.ETHERNET])
    }

    @Test
    fun switchingTransportStopsThePreviousTransportsRate() {
        val engine = engine()
        val wifi = run(engine, seconds = 2, rxPerSecond = 300_000L, txPerSecond = 0L)
        assertEquals(300_000L, wifi.last().wifiDownloadBps)

        // Wi-Fi dropped: the attribution must not keep reporting Wi-Fi speed.
        engine.setTransport(Transport.NONE)
        assertEquals(0L, engine.snapshot().wifiDownloadBps)
    }

    @Test
    fun sessionResetClearsTotalsButKeepsMeasuring() {
        val engine = engine()
        run(engine, seconds = 2, rxPerSecond = 10_000L, txPerSecond = 0L)
        engine.resetSession(SpeedSample(5_000_000L, 5_000L, 10_000L))

        val snapshot = engine.snapshot()
        assertEquals(0L, snapshot.sessionRxBytes)
        assertEquals(0L, snapshot.peakDownloadBps)
        assertTrue(engine.attributedTotals().isEmpty())

        val published = engine.onSample(SpeedSample(5_100_000L, 5_000L, 11_000L))
        assertEquals(100_000L, published!!.downloadBps)
    }

    @Test
    fun steadyValueSmoothsTheRawRate() {
        val engine = engine()
        val published = run(engine, seconds = 3, rxPerSecond = 100_000L, txPerSecond = 0L)
        // Constant input → the EWMA converges to the same value.
        assertEquals(100_000L, published.last().steadyDownloadBps)
    }

    @Test
    fun restoredLastSpeedIsVisibleImmediatelyAndThenReplaced() {
        val engine = engine()
        engine.restoreLastKnown(9_000L, 1_000L)
        assertEquals(9_000L, engine.snapshot().steadyDownloadBps)

        // First real measurement overwrites it: no stale value can stick around.
        engine.setTransport(Transport.WIFI)
        engine.seed(SpeedSample(0L, 0L, 0L))
        val published = engine.onSample(SpeedSample(20_000L, 0L, 1_000L))
        assertEquals(20_000L, published!!.steadyDownloadBps)
    }

    @Test
    fun transferBytesArithmeticIsSafe() {
        val a = TransferBytes(1_000L, 500L)
        val b = TransferBytes(400L, 100L)
        assertEquals(TransferBytes(600L, 400L), a - b)
        assertEquals(1_500L, a.total)
        assertEquals(600L, TransferBytes(500L, 100L).total)
    }
}
