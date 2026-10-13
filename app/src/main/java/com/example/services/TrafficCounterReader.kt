package com.example.services

import android.net.TrafficStats
import android.os.SystemClock
import java.io.File

enum class CounterSource(val label: String) {
    TRAFFIC_STATS("TrafficStats"),
    PROC_NET_DEV("/proc/net/dev"),
}

/**
 * Network interface kinds, derived from the *kernel* interface name.
 *
 * Only used for diagnostics and for the (optional) per-interface detail view — the live
 * speed itself comes from the device-wide counters, exactly like the system does it.
 */
enum class InterfaceKind {
    LOOPBACK,
    WIFI,
    MOBILE,
    ETHERNET,
    TUNNEL,
    OTHER,
}

/**
 * Reads the device-wide network counters.
 *
 * Two independent OS sources are supported, and the same physical truth is behind both:
 *
 *  - [CounterSource.TRAFFIC_STATS] (primary): `TrafficStats.getTotalRxBytes()` /
 *    `getTotalTxBytes()`. No permission needed. The kernel marks the counters
 *    `UNSUPPORTED` (-1) on a few ROMs — that is detected per direction instead of being
 *    silently reported as "0 bytes".
 *  - [CounterSource.PROC_NET_DEV] (fallback): byte counters of every non-loopback
 *    interface parsed straight from `/proc/net/dev`.
 *
 * The two directions are always read from *one* coherent pair of counters, so the
 * download figure can never be mirrored into the upload figure.
 */
object TrafficCounterReader {

    private const val PROC_NET_DEV_PATH = "/proc/net/dev"

    fun read(source: CounterSource, atMs: Long = SystemClock.elapsedRealtime()): SpeedSample =
        when (source) {
            CounterSource.TRAFFIC_STATS -> readTrafficStats(atMs)
            CounterSource.PROC_NET_DEV -> readProcNetDev(atMs)
        }

    fun other(source: CounterSource): CounterSource =
        if (source == CounterSource.TRAFFIC_STATS) CounterSource.PROC_NET_DEV
        else CounterSource.TRAFFIC_STATS

    /** System-wide counters (all interfaces except loopback) via `TrafficStats`. */
    fun readTrafficStats(atMs: Long = SystemClock.elapsedRealtime()): SpeedSample {
        return try {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            SpeedSample(
                rx = if (rx > 0L) rx else 0L,
                tx = if (tx > 0L) tx else 0L,
                atMs = atMs,
                rxSupported = rx >= 0L,
                txSupported = tx >= 0L
            )
        } catch (t: Throwable) {
            SpeedSample(0L, 0L, atMs, rxSupported = false, txSupported = false)
        }
    }

    /** Kernel counters summed over every non-loopback interface in `/proc/net/dev`. */
    fun readProcNetDev(atMs: Long = SystemClock.elapsedRealtime()): SpeedSample {
        return try {
            val content = File(PROC_NET_DEV_PATH).readText()
            val (rx, tx) = parseProcNetDev(content)
            SpeedSample(rx = rx, tx = tx, atMs = atMs)
        } catch (t: Throwable) {
            SpeedSample(0L, 0L, atMs, rxSupported = false, txSupported = false)
        }
    }

    /**
     * Pure parser for `/proc/net/dev` (unit-testable).
     *
     * File layout (example):
     * ```
     * Inter-|   Receive                                                |  Transmit
     * face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
     *    lo: 104344487    6158    0    0    0     0          0         0 104344487    6158    0    0    0     0       0          0
     * wlan0: 842351776  102341    0    0    0     0          0         0  91234123   98712    0    0    0     0       0          0
     * ```
     * Column 1 after the colon = received bytes, column 9 = transmitted bytes.
     */
    fun parseProcNetDev(content: String): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        for ((iface, bytes) in parseProcNetDevPerInterface(content)) {
            if (iface == "lo") continue // loopback is not internet traffic
            rx += bytes.rx
            tx += bytes.tx
        }
        return rx to tx
    }

    /**
     * Per-interface totals — used by the diagnostics panel so the user can see the raw
     * counters the meter is built on (`wlan0`, `rmnet_data0`, …).
     */
    fun parseProcNetDevPerInterface(content: String): Map<String, TransferBytes> {
        val result = LinkedHashMap<String, TransferBytes>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue // header lines have no "iface:" marker
            val iface = line.substring(0, colon).trim()
            if (iface.isEmpty()) continue
            val fields = line.substring(colon + 1)
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (fields.size < 9) continue
            val rx = fields[0].toLongOrNull() ?: continue
            val tx = fields[8].toLongOrNull() ?: continue
            val previous = result[iface] ?: TransferBytes()
            result[iface] = TransferBytes(previous.rx + rx, previous.tx + tx)
        }
        return result
    }

    /** Live per-interface snapshot for the diagnostics panel (empty when unreadable). */
    fun readInterfaceTotals(): Map<String, TransferBytes> {
        return try {
            parseProcNetDevPerInterface(File(PROC_NET_DEV_PATH).readText())
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    fun interfaceKind(iface: String): InterfaceKind {
        val name = iface.lowercase()
        return when {
            name.startsWith("lo") -> InterfaceKind.LOOPBACK
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") ->
                InterfaceKind.WIFI
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                name.startsWith("wwan") || name.startsWith("clat") || name.startsWith("v4-") ->
                InterfaceKind.MOBILE
            name.startsWith("eth") || name.startsWith("rndis") -> InterfaceKind.ETHERNET
            name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("tap") ->
                InterfaceKind.TUNNEL
            else -> InterfaceKind.OTHER
        }
    }
}
