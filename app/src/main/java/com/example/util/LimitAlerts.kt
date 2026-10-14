package com.example.util

import java.util.Locale

/**
 * The warn-only limit rule shared by the daily and the monthly mobile limit.
 *
 * The app never blocks or throttles anything — it shows one prominent warning per
 * threshold (50 %, 90 %, 100 %) per period, and swiping the dialog away counts as having
 * seen it. Pure functions, unit-tested in `LimitAlertsTest`.
 */
object LimitAlerts {

    /** Thresholds in ascending order; the highest reached one is the pending level. */
    val LEVELS: List<Long> = listOf(50L, 90L, 100L)

    /** The stored limit (value + "MB"/"GB") in bytes; 0 when no limit is set. */
    fun limitBytes(limit: Double, unit: String): Long {
        if (limit <= 0.0) return 0L
        val factor = if (unit.uppercase(Locale.ROOT) == "GB") 1024L * 1024L * 1024L else 1024L * 1024L
        return (limit * factor).toLong()
    }

    /**
     * The threshold that is due right now, or null: the highest level reached by
     * [usedBytes] against [limitBytes] that is above the level already dismissed in this
     * period ([dismissedLevel], 0 when nothing was dismissed yet).
     */
    fun pendingLevel(usedBytes: Long, limitBytes: Long, dismissedLevel: Long): Long? {
        if (limitBytes <= 0L || usedBytes < 0L) return null
        val pct = usedBytes.toDouble() / limitBytes.toDouble() * 100.0
        val reached = LEVELS.lastOrNull { pct >= it } ?: return null
        return if (reached > dismissedLevel) reached else null
    }

    /** 0.0 .. 1.0 share of the limit used; null when no limit is set. */
    fun progress(usedBytes: Long, limitBytes: Long): Double? {
        if (limitBytes <= 0L) return null
        return (usedBytes.toDouble() / limitBytes.toDouble()).coerceIn(0.0, 1.0)
    }
}
