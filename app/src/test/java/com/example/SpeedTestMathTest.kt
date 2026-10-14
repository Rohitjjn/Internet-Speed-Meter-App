package com.example

import com.example.data.DataUsageEntity
import com.example.services.SpeedTestEngine
import com.example.util.DeltaMath
import com.example.util.UsageCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure maths behind the numbers the user sees: the measured-window throughput of the
 * speed test, the "vs last …" percentages and the CSV export. No Android, no network.
 */
class SpeedTestMathTest {

    private val ms = 1_000_000L // ns per ms

    // ------------------------------------------------------------------ request sizing

    @Test
    fun aQuicklyFinishedRequestDoublesTheNextOneUpToTheCap() {
        val start = SpeedTestEngine.INITIAL_REQUEST_BYTES
        // Gigabit line: the 20 MB body was read in 180 ms → ask for 40 MB next.
        assertEquals(start * 2, SpeedTestEngine.nextRequestBytes(start, completedInMs = 180L, bodyComplete = true))
        // Keeps doubling while requests stay fast, never beyond the cap.
        var size = start
        repeat(10) { size = SpeedTestEngine.nextRequestBytes(size, 100L, true) }
        assertEquals(SpeedTestEngine.MAX_REQUEST_BYTES, size)
    }

    @Test
    fun aSlowOrCutOffRequestKeepsItsSize() {
        val start = SpeedTestEngine.INITIAL_REQUEST_BYTES
        // 20 MB took 2.4 s: the pipe is already busy, no need for a bigger body.
        assertEquals(start, SpeedTestEngine.nextRequestBytes(start, completedInMs = 2_400L, bodyComplete = true))
        // Cut off by the deadline after 300 ms: that says nothing about the line.
        assertEquals(start, SpeedTestEngine.nextRequestBytes(start, completedInMs = 300L, bodyComplete = false))
        // Exactly the threshold is not "fast".
        assertEquals(start, SpeedTestEngine.nextRequestBytes(start, SpeedTestEngine.FAST_REQUEST_MS, true))
    }

    // ------------------------------------------------------------------ throughput window

    @Test
    fun throughputIsAveragedOverTheMeasuredWindowAfterTheGracePeriod() {
        // 8 s phase: first byte at 200 ms, grace ends at 1.5 s with 1 MB in, 9 MB total.
        val result = SpeedTestEngine.computeThroughput(
            totalBytes = 9_000_000L,
            startNs = 0L,
            firstByteNs = 200 * ms,
            graceNs = 1_500 * ms,
            graceBytes = 1_000_000L,
            endNs = 8_000 * ms,
            streams = 4
        )
        // Window = 1.5 s → 8 s = 6.5 s, bytes = 8 MB. Warm-up excluded, total still reported.
        assertEquals(6_500L, result.durationMs)
        assertEquals(8_000_000L, result.bytes)
        assertEquals(9_000_000L, result.totalBytes)
        assertEquals(8_000_000L * 8.0 / 6.5, result.bitsPerSecond, 1.0)
        assertEquals(4, result.streams)
    }

    @Test
    fun aTooShortPostGraceWindowFallsBackToTheFirstByte() {
        // The phase was aborted/ended 600 ms after the grace period: averaging 600 ms would
        // be noise, so the whole measured span from the first byte is used instead.
        val result = SpeedTestEngine.computeThroughput(
            totalBytes = 3_000_000L,
            startNs = 0L,
            firstByteNs = 100 * ms,
            graceNs = 1_500 * ms,
            graceBytes = 2_000_000L,
            endNs = 2_100 * ms,
            streams = 2
        )
        assertEquals(2_000L, result.durationMs)
        assertEquals(3_000_000L, result.bytes)
    }

    @Test
    fun noBytesAfterTheGracePeriodMeansTheWholeSpanIsUsed() {
        val result = SpeedTestEngine.computeThroughput(
            totalBytes = 500_000L,
            startNs = 0L,
            firstByteNs = 50 * ms,
            graceNs = 1_500 * ms,
            graceBytes = 500_000L,
            endNs = 8_000 * ms,
            streams = 4
        )
        assertEquals(7_950L, result.durationMs)
        assertEquals(500_000L, result.bytes)
    }

    @Test
    fun aMissingFirstByteNeverDividesByZero() {
        val result = SpeedTestEngine.computeThroughput(
            totalBytes = 0L,
            startNs = 10 * ms,
            firstByteNs = 0L,
            graceNs = 10 * ms,
            graceBytes = 0L,
            endNs = 10 * ms,
            streams = 1
        )
        assertEquals(1L, result.durationMs)
        assertEquals(0.0, result.bitsPerSecond, 0.0)
    }

    // ------------------------------------------------------------------ deltas

    @Test
    fun percentChangeIsSignedAndRounded() {
        assertEquals(50, DeltaMath.percentChange(150.0, 100.0))
        assertEquals(-25, DeltaMath.percentChange(75.0, 100.0))
        assertEquals(0, DeltaMath.percentChange(100.0, 100.0))
        assertEquals(33, DeltaMath.percentChange(4.0, 3.0))
        assertEquals(-100, DeltaMath.percentChange(0.0, 3.0))
    }

    @Test
    fun percentChangeHasNoAnswerWithoutABaseline() {
        assertNull(DeltaMath.percentChange(10.0, 0.0))
        assertNull(DeltaMath.percentChange(10.0, -1.0))
        assertNull(DeltaMath.percentChange(Double.NaN, 5.0))
        assertNull(DeltaMath.percentChange(Double.POSITIVE_INFINITY, 5.0))
    }

    // ------------------------------------------------------------------ CSV export

    @Test
    fun csvHasAHeaderAndOneRawByteRowPerDayInTheGivenOrder() {
        val rows = listOf(
            DataUsageEntity("2026-10-14", mobileRx = 1L, mobileTx = 2L, wifiRx = 3L, wifiTx = 4L),
            DataUsageEntity("2026-10-13", mobileRx = 1_073_741_824L, mobileTx = 0L, wifiRx = 0L, wifiTx = 5L)
        )
        val csv = UsageCsv.build(rows)
        val lines = csv.split('\n')
        assertEquals(UsageCsv.HEADER, lines[0])
        assertEquals("2026-10-14,1,2,3,4,10", lines[1])
        assertEquals("2026-10-13,1073741824,0,0,5,1073741829", lines[2])
        assertEquals("ends with a newline", "", lines[3])
        assertEquals(4, lines.size)
        assertTrue("no rounding, no units — raw bytes only", !csv.contains("MB") && !csv.contains("GB"))
    }

    @Test
    fun csvOfNothingIsJustTheHeader() {
        assertEquals(UsageCsv.HEADER + "\n", UsageCsv.build(emptyList()))
    }
}
