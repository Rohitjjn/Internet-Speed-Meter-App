package com.example

import com.example.util.LimitAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The warn-only limit rule used by both the daily and the monthly mobile limit. It decides
 * when the user sees a warning dialog, so every edge (no limit, already dismissed, jumping
 * straight past two thresholds, the unit conversion) is pinned down.
 */
class LimitAlertsTest {

    private val gb = 1024L * 1024L * 1024L
    private val mb = 1024L * 1024L

    @Test
    fun limitBytesFollowsTheUnitAndTreatsZeroAsNoLimit() {
        assertEquals(2L * gb, LimitAlerts.limitBytes(2.0, "GB"))
        assertEquals(500L * mb, LimitAlerts.limitBytes(500.0, "MB"))
        assertEquals((1.5 * gb).toLong(), LimitAlerts.limitBytes(1.5, "gb"))
        assertEquals(0L, LimitAlerts.limitBytes(0.0, "GB"))
        assertEquals(0L, LimitAlerts.limitBytes(-3.0, "GB"))
    }

    @Test
    fun theHighestReachedThresholdIsPendingUntilDismissed() {
        val limit = 1L * gb
        assertNull(LimitAlerts.pendingLevel(usedBytes = limit / 4, limitBytes = limit, dismissedLevel = 0L))
        assertEquals(50L, LimitAlerts.pendingLevel(limit / 2, limit, 0L))
        // Dismissed 50 → quiet until 90.
        assertNull(LimitAlerts.pendingLevel(limit * 6 / 10, limit, 50L))
        assertEquals(90L, LimitAlerts.pendingLevel(limit * 9 / 10, limit, 50L))
        assertEquals(100L, LimitAlerts.pendingLevel(limit, limit, 90L))
        // Over the limit, everything already seen: nothing more to say (warn-only, no block).
        assertNull(LimitAlerts.pendingLevel(limit * 2, limit, 100L))
    }

    @Test
    fun jumpingPastSeveralThresholdsShowsOnlyTheHighest() {
        val limit = 1L * gb
        // A big transfer took the day from 10 % to 95 %: one dialog (90), not three.
        assertEquals(90L, LimitAlerts.pendingLevel(limit * 95 / 100, limit, 0L))
    }

    @Test
    fun noLimitOrNegativeUsageMeansNoAlertAndNoProgress() {
        assertNull(LimitAlerts.pendingLevel(5L * gb, 0L, 0L))
        assertNull(LimitAlerts.pendingLevel(-1L, 1L * gb, 0L))
        assertNull(LimitAlerts.progress(100L, 0L))
        assertEquals(0.5, LimitAlerts.progress(512L * mb, 1L * gb)!!, 0.0001)
        assertEquals(1.0, LimitAlerts.progress(3L * gb, 1L * gb)!!, 0.0)
    }
}
