package com.example.services

import android.net.TrafficStats
import android.os.SystemClock
import java.io.File

/** One snapshot of device-wide counters: bytes received/sent + monotonic timestamp. */
data class TrafficSample(
    val rx: Long,
    val tx: Long,
    val timestampMs: Long
) {
    val total: Long get() = rx + tx
    val isZero: Boolean get() = rx == 0L && tx == 0L

    /** True when either counter advanced since [other]. */
    fun movedFrom(other: TrafficSample): Boolean = rx > other.rx || tx > other.tx
}

enum class CounterSource(val label: String) {
    TRAFFIC_STATS("TrafficStats"),
    PROC_NET_DEV("/proc/net/dev"),
}

/**
 * Reads device-wide network counters from two independent sources.
 *
 * This is what makes the meter report the REAL speed the way other speed-meter
 * apps do:
 *
 *  - [CounterSource.TRAFFIC_STATS] (primary): the same system-wide counters
 *    used by most speedometer apps. Works without special permissions.
 *  - [CounterSource.PROC_NET_DEV] (fallback): byte counters of every network
 *    interface parsed straight from the kernel. On some devices/ROMs the
 *    TrafficStats totals are restricted, stale or report 0 — the kernel file
 *    is the ground truth there.
 *
 * The ticker [SpeedMeterService] health-checks both sources every second and
 * automatically switches when the active one stops moving.
 */
object TrafficCounterReader {

    /** Primary source: system-wide counters (ACCESS_NETWORK_STATE). */
    fun readTrafficStats(): TrafficSample {
        return try {
            TrafficSample(
                rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L),
                tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L),
                timestampMs = SystemClock.elapsedRealtime()
            )
        } catch (t: Throwable) {
            TrafficSample(0L, 0L, SystemClock.elapsedRealtime())
        }
    }

    /** Fallback source: sums bytes from /proc/net/dev across all non-loopback interfaces. */
    fun readProcNetDev(): TrafficSample {
        return try {
            val (rx, tx) = parseProcNetDev(File("/proc/net/dev").readText())
            TrafficSample(rx, tx, SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            TrafficSample(0L, 0L, SystemClock.elapsedRealtime())
        }
    }

    fun read(source: CounterSource): TrafficSample = when (source) {
        CounterSource.TRAFFIC_STATS -> readTrafficStats()
        CounterSource.PROC_NET_DEV -> readProcNetDev()
    }

    /**
     * Pure parser for /proc/net/dev (unit-testable).
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
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue // header lines have no "iface:" marker
            val iface = line.substring(0, colon).trim()
            if (iface == "lo") continue // skip loopback
            val fields = line.substring(colon + 1)
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (fields.size < 9) continue
            rx += fields[0].toLongOrNull() ?: 0L
            tx += fields[8].toLongOrNull() ?: 0L
        }
        return rx to tx
    }
}
