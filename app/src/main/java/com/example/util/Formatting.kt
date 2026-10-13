package com.example.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure formatting helpers (unit-testable, no Android dependencies).
 */
object SpeedFormatter {

    /** "12.4 MB/s" style value. */
    fun format(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }

    /**
     * Splits the value into (number, unit) for large hero-style display.
     * Example: (12.4, "MB/s") or (842, "KB/s").
     */
    fun split(bytesPerSec: Long): Pair<String, String> {
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            val mb = kb / 1024.0
            val number = if (mb >= 100.0) String.format(Locale.US, "%.0f", mb) else String.format(Locale.US, "%.1f", mb)
            number to "MB/s"
        } else {
            kb.toInt().toString() to "KB/s"
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
