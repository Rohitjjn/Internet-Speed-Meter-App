package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Display unit for live speeds.
 *
 * [BYTES] (KB/s, MB/s) is what most device meters show; [BITS] (Kbps, Mbps) is what ISPs
 * and speed-test services advertise, so users can compare the meter with their plan.
 * Daily data usage is always shown in bytes (MB/GB) — that is how data packs are sold.
 */
enum class SpeedUnit(val key: String) {
    BYTES("BYTES"),
    BITS("BITS");

    companion object {
        fun fromKey(key: String?): SpeedUnit =
            entries.firstOrNull { it.key == key } ?: BYTES
    }
}

/**
 * Pure formatting helpers (unit-testable, no Android dependencies).
 */
object SpeedFormatter {

    /** "12.4 MB/s" / "99.2 Mbps" style value. */
    fun format(bytesPerSec: Long, unit: SpeedUnit = SpeedUnit.BYTES): String {
        val (number, suffix) = split(bytesPerSec, unit)
        return "$number $suffix"
    }

    /**
     * Splits the value into (number, unit) for large hero-style display.
     * Example: (12.4, "MB/s") or (842, "KB/s") — or (99.2, "Mbps") in bits mode.
     */
    fun split(bytesPerSec: Long, unit: SpeedUnit = SpeedUnit.BYTES): Pair<String, String> {
        val safe = if (bytesPerSec > 0L) bytesPerSec else 0L
        return when (unit) {
            SpeedUnit.BYTES -> {
                val kb = safe / 1024.0
                if (kb >= 1024.0) {
                    decimal(kb / 1024.0) to "MB/s"
                } else {
                    // Rounded, not truncated: a meter that says 512 while 512.9 KB crossed
                    // the wire is showing a wrong number.
                    kb.roundToLong().toString() to "KB/s"
                }
            }
            SpeedUnit.BITS -> {
                // Decimal (1000-based) kilobits/megabits — the unit ISPs advertise.
                val kbps = safe * 8.0 / 1000.0
                if (kbps >= 1000.0) {
                    decimal(kbps / 1000.0) to "Mbps"
                } else {
                    kbps.roundToLong().toString() to "Kbps"
                }
            }
        }
    }

    /**
     * One decimal, but never a pointless trailing ".0" — "104.9", "100", "2.5".
     * Used for every hero/gauge number so the digits stay readable at any magnitude.
     */
    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    /** "99.2 Mbps" style value straight from a bits-per-second measurement. */
    fun formatBits(bitsPerSecond: Double): String {
        val (number, suffix) = splitBits(bitsPerSecond)
        return "$number $suffix"
    }

    /** (number, unit) for a bits-per-second value — used by the speed-test gauge. */
    fun splitBits(bitsPerSecond: Double): Pair<String, String> {
        val safe = if (bitsPerSecond > 0.0) bitsPerSecond else 0.0
        val kbps = safe / 1000.0
        return if (kbps >= 1000.0) {
            decimal(kbps / 1000.0) to "Mbps"
        } else {
            kbps.roundToLong().toString() to "Kbps"
        }
    }
}

/**
 * The speed-test gauge scale.
 *
 * The maximum is picked from a **fixed ladder** instead of being derived from the current
 * peak: a scale that resized itself on every tick would make the needle meaningless
 * (it would sit at the same spot no matter how fast the line is). The ladder covers
 * 10 Mbit/s (a slow mobile line) to 5 Gbit/s (a fast home connection).
 */
object GaugeScale {

    /** Bits per second, ascending. The first step that fits the value wins. */
    private val LADDER_BPS = listOf(
        10_000_000.0,
        25_000_000.0,
        50_000_000.0,
        100_000_000.0,
        250_000_000.0,
        500_000_000.0,
        1_000_000_000.0,
        2_500_000_000.0,
        5_000_000_000.0
    )

    /**
     * Smallest ladder step that can display [bitsPerSecond] with a little headroom, or
     * the top of the ladder when the value is faster than anything we can show.
     */
    fun maxBps(bitsPerSecond: Double): Double {
        val safe = if (bitsPerSecond.isFinite() && bitsPerSecond > 0.0) bitsPerSecond else 0.0
        val needed = safe * 1.15
        return LADDER_BPS.firstOrNull { it >= needed } ?: LADDER_BPS.last()
    }
}

object BytesFormatter {

    /** "1.24 GB" / "38.1 MB" style value. */
    fun format(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    fun toMb(bytes: Long): Double = bytes / (1024.0 * 1024.0)
}

object DateFormatter {

    fun day(date: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)

    fun shortDay(date: Date = Date()): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(date)

    fun readable(dateStr: String, todayStr: String): String {
        if (dateStr == todayStr) return "Today"
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
            if (date != null) {
                SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(date)
            } else {
                dateStr
            }
        } catch (e: Exception) {
            dateStr
        }
    }
}
