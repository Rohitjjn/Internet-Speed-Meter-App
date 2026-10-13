package com.example

import com.example.data.SpeedTestResult
import com.example.services.InterfaceKind
import com.example.services.SpeedTestEngine
import com.example.services.TrafficCounterReader
import com.example.util.BytesFormatter
import com.example.util.SpeedFormatter
import com.example.util.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertTrue(mbValue.contains('.')) // 12.4 has one decimal
    }

    // ---------- BytesFormatter ----------

    @Test
    fun bytesFormatMbAndGb() {
        assertEquals("0.0 MB", BytesFormatter.format(0L))
        assertEquals("256.0 MB", BytesFormatter.format(256L * 1024L * 1024L))
        assertEquals("1.00 GB", BytesFormatter.format(1024L * 1024L * 1024L))
    }

    // ---------- bits/bytes display modes ----------

    @Test
    fun speedUnitModesRenderTheSameMeasurementDifferently() {
        // 12.5 MB/s is also 100 Mbps — the number an ISP advertises.
        val bytesPerSecond = (12.5 * 1024.0 * 1024.0).toLong()
        assertEquals("12.5 MB/s", SpeedFormatter.format(bytesPerSecond, SpeedUnit.BYTES))
        // 12.5 MB/s = 12.5 × 1024 × 1024 × 8 bits/s = 104.9 Mbps (decimal, ISP unit).
        assertEquals("104.9 Mbps", SpeedFormatter.format(bytesPerSecond, SpeedUnit.BITS))

        assertEquals("0 KB/s", SpeedFormatter.format(0L, SpeedUnit.BYTES))
        assertEquals("0 Kbps", SpeedFormatter.format(0L, SpeedUnit.BITS))
        assertEquals(SpeedUnit.BITS, SpeedUnit.fromKey("BITS"))
        assertEquals(SpeedUnit.BYTES, SpeedUnit.fromKey("nonsense"))
    }

    @Test
    fun bitsSplitUsesDecimalUnits() {
        val (value, unit) = SpeedFormatter.splitBits(100_000_000.0) // 100 Mbit/s
        assertEquals("100", value)
        assertEquals("Mbps", unit)

        val (slowValue, slowUnit) = SpeedFormatter.splitBits(2_500_000.0) // 2.5 Mbit/s
        assertEquals("2.5", slowValue)
        assertEquals("Mbps", slowUnit)

        assertEquals("Kbps", SpeedFormatter.splitBits(512_000.0).second)
    }

    // ---------- latency jitter (speed test) ----------

    @Test
    fun jitterIsTheMeanAbsoluteDifferenceOfConsecutiveSamples() {
        assertEquals(0L, SpeedTestEngine.jitterOf(listOf(20L)))
        assertEquals(0L, SpeedTestEngine.jitterOf(listOf(20L, 20L)))
        assertEquals(10L, SpeedTestEngine.jitterOf(listOf(20L, 30L, 20L)))
    }

    // ---------- speed test result persistence ----------

    @Test
    fun speedTestResultsRoundTripThroughTheirCompactFormat() {
        val results = listOf(
            SpeedTestResult(1_700_000_000_000L, 12_500_000.0, 1_250_000.0, 24L, 6L, "speed.cloudflare.com"),
            SpeedTestResult(1_700_000_100_000L, 8_000_000.0, 900_000.0, 31L, 9L, "speed.cloudflare.com")
        )
        val decoded = SpeedTestResult.decode(SpeedTestResult.encode(results))
        assertEquals(2, decoded.size)
        assertEquals(12_500_000.0, decoded[0].downloadBps, 0.5)
        assertEquals(24L, decoded[0].latencyMs)
        assertEquals("speed.cloudflare.com", decoded[1].server)
    }

    @Test
    fun malformedSpeedTestLinesAreIgnored() {
        assertTrue(SpeedTestResult.decode("").isEmpty())
        assertTrue(SpeedTestResult.decode("garbage").isEmpty())
        assertTrue(SpeedTestResult.decode("1|2|3").isEmpty()) // too few fields
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
    fun parseProcNetDevPerInterfaceKeepsEveryInterfaceForDiagnostics() {
        val perInterface = TrafficCounterReader.parseProcNetDevPerInterface(sampleProcNetDev)
        assertEquals(842351776L, perInterface["wlan0"]?.rx)
        assertEquals(91234123L, perInterface["wlan0"]?.tx)
        assertEquals(512000L, perInterface["rmnet0"]?.rx)
        assertEquals(104344487L, perInterface["lo"]?.rx) // kept for the panel, excluded from totals

        assertEquals(InterfaceKind.WIFI, TrafficCounterReader.interfaceKind("wlan0"))
        assertEquals(InterfaceKind.MOBILE, TrafficCounterReader.interfaceKind("rmnet_data0"))
        assertEquals(InterfaceKind.ETHERNET, TrafficCounterReader.interfaceKind("eth0"))
        assertEquals(InterfaceKind.TUNNEL, TrafficCounterReader.interfaceKind("tun0"))
        assertEquals(InterfaceKind.LOOPBACK, TrafficCounterReader.interfaceKind("lo"))
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
