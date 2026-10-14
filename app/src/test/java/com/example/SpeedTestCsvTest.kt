package com.example

import com.example.data.SpeedTestResult
import com.example.util.SpeedTestCsv
import org.junit.Assert.assertEquals
import org.junit.Test

/** The speed-test export must be unambiguous about units and independent of the phone's locale/zone. */
class SpeedTestCsvTest {

    @Test
    fun csvCarriesBothBytesPerSecondAndMbpsPlusAUtcTimestamp() {
        val rows = listOf(
            SpeedTestResult(
                atMillis = 1_760_400_000_000L, // 2025-10-14T00:00:00Z
                downloadBps = 12_500_000.0,     // bytes/s = 100 Mbps
                uploadBps = 1_250_000.0,        // bytes/s = 10 Mbps
                latencyMs = 18L,
                jitterMs = 2L,
                server = "speed.cloudflare.com"
            )
        )
        val lines = SpeedTestCsv.build(rows).trimEnd().lines()
        assertEquals(2, lines.size)
        assertEquals(SpeedTestCsv.HEADER, lines[0])
        assertEquals(
            "2025-10-14T00:00:00Z,1760400000000,12500000.00,100.00,1250000.00,10.00,18,2,speed.cloudflare.com",
            lines[1]
        )
    }

    @Test
    fun emptyHistoryIsJustTheHeaderAndCommasInHostsAreNeutralised() {
        assertEquals(SpeedTestCsv.HEADER + "\n", SpeedTestCsv.build(emptyList()))
        val csv = SpeedTestCsv.build(
            listOf(SpeedTestResult(0L, 0.0, 0.0, 0L, 0L, "a,b"))
        )
        assertEquals("1970-01-01T00:00:00Z,0,0.00,0.00,0.00,0.00,0,0,a b", csv.trimEnd().lines()[1])
    }
}
