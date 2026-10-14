package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
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

    /**
     * The **storage key** of a day (`daily_data_usage.date`, the reset baseline, the
     * month prefix of the recap query). Always Latin digits via [Locale.US]: a device
     * locale with its own numbering system (Marathi, Nepali, Arabic, …) would otherwise
     * write `२०२६-१०-१४`, and the rows written before and after a language change would
     * never match again. Display formats below stay in the user's locale.
     */
    fun day(date: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

    /** `yyyy-MM` key prefix used by the monthly recap queries. */
    fun monthPrefix(date: Date = Date()): String =
        SimpleDateFormat("yyyy-MM", Locale.US).format(date)

    fun shortDay(date: Date = Date()): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(date)

    /**
     * Human date for a stored key; [todayLabel] is the localized word shown for today's
     * row (resources are not reachable from this pure helper).
     */
    fun readable(dateStr: String, todayStr: String, todayLabel: String = "Today"): String {
        if (dateStr == todayStr) return todayLabel
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)
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

/**
 * Percentage comparisons used by the "vs last test" / "vs last month" chips. Pure maths
 * over two real values — the caller renders the localized label.
 */
object DeltaMath {
    /**
     * Signed, rounded percentage change of [current] against [previous], or `null` when
     * there is no usable baseline (no previous value, or a non-finite input).
     */
    fun percentChange(current: Double, previous: Double): Int? {
        if (previous <= 0.0 || !current.isFinite() || !previous.isFinite()) return null
        val change = (current - previous) / previous * 100.0
        if (!change.isFinite()) return null
        return change.roundToInt()
    }
}
