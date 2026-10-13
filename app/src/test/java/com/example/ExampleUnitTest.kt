package com.example

import com.example.services.TrafficCounterReader
import com.example.util.BytesFormatter
import com.example.util.SpeedFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure (no Android) unit tests for the speed engine helpers.
 */
class ExampleUnitTest {

    // ---------- SpeedFormatter ----------

    @Test
    fun speedFormatsBytesPerSecond() {
        assertEquals("0 KB/s", SpeedFormatter.format(0L))
        assertEquals("512 KB/s", SpeedFormatter.format(512L * 1024L))
        assertEquals("1.5 MB/s", SpeedFormatter.format((1.5 * 1024.0 * 1024.0).toLong()))
    }

    @Test
    fun speedSplitReturnsNumberAndUnit() {
        val (value, unit) = SpeedFormatter.split(842L * 1024L)
        assertEquals("842", value)
        assertEquals("KB/s", unit)

        // 12.4 MB in bytes
        val (mbValue, mbUnit) = SpeedFormatter.split(12_999_342L)
        assertEquals("MB/s", mbUnit)
        org.junit.Assert.assertTrue(mbValue.contains('.')) // 12.4 has one decimal
    }

    // ---------- BytesFormatter ----------

    @Test
    fun bytesFormatMbAndGb() {
        assertEquals("0.0 MB", BytesFormatter.format(0L))
        assertEquals("256.0 MB", BytesFormatter.format(256L * 1024L * 1024L))
        assertEquals("1.00 GB", BytesFormatter.format(1024L * 1024L * 1024L))
    }

    // ---------- /proc/net/dev parser (the meter's fallback source) ----------

    private val sampleProcNetDev = """
        Inter-|   Receive                                                |  Transmit
        face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
           lo: 104344487    6158    0    0    0     0          0         0 104344487    6158    0    0    0     0       0          0
          wlan0: 842351776  102341    0    0    0     0          0         0  91234123   98712    0    0    0     0       0          0
          rmnet0:   512000     812    0    0    0     0          0         0   120000     300    0    0    0     0       0          0
    """.trimIndent()

    @Test
    fun parseProcNetDevSumsNonLoopbackInterfaces() {
        val (rx, tx) = TrafficCounterReader.parseProcNetDev(sampleProcNetDev)
        // lo excluded
        assertEquals(842351776L + 512000L, rx)
        assertEquals(91234123L + 120000L, tx)
    }

    @Test
    fun parseProcNetDevHandlesEmptyContent() {
        val (rx, tx) = TrafficCounterReader.parseProcNetDev("")
        assertEquals(0L, rx)
        assertEquals(0L, tx)
    }

    @Test
    fun parseProcNetDevIgnoresMalformedLines() {
        val content = """
            Inter-|   Receive
            iface |bytes
               eth0: not_a_number
        """.trimIndent()
        val (rx, tx) = TrafficCounterReader.parseProcNetDev(content)
        assertEquals(0L, rx)
        assertEquals(0L, tx)
    }
}
