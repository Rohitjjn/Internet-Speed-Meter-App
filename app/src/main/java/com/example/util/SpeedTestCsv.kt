package com.example.util

import com.example.data.SpeedTestResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * CSV of the stored speed-test results (newest first, exactly as the screen lists them).
 *
 * Pure function. Speeds are exported twice so no reader has to guess the unit: the raw
 * stored value in **bytes per second** and the decimal **megabits per second** ISPs
 * advertise (`bytes × 8 / 1 000 000`). Timestamps are ISO-8601 in UTC plus the raw epoch
 * millis — a spreadsheet can localise, the file must not depend on the phone's zone.
 */
object SpeedTestCsv {

    const val HEADER = "at_utc,at_epoch_ms,download_bytes_per_s,download_mbps,upload_bytes_per_s,upload_mbps,latency_ms,jitter_ms,server"

    fun build(results: List<SpeedTestResult>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val out = StringBuilder(HEADER.length + results.size * 96)
        out.append(HEADER).append('\n')
        for (r in results) {
            out.append(iso.format(java.util.Date(r.atMillis))).append(',')
                .append(r.atMillis).append(',')
                .append(plain(r.downloadBps)).append(',')
                .append(plain(r.downloadBps * 8.0 / 1_000_000.0)).append(',')
                .append(plain(r.uploadBps)).append(',')
                .append(plain(r.uploadBps * 8.0 / 1_000_000.0)).append(',')
                .append(r.latencyMs).append(',')
                .append(r.jitterMs).append(',')
                .append(r.server.replace(',', ' ')).append('\n')
        }
        return out.toString()
    }

    /** Fixed two decimals, Latin digits and a dot regardless of locale (CSV is not prose). */
    private fun plain(value: Double): String = String.format(Locale.US, "%.2f", value)
}
