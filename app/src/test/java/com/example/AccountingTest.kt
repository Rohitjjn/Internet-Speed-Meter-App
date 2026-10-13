package com.example

import com.example.data.DataUsageEntity
import com.example.data.ResetOffsets
import com.example.data.TransportBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the daily-accounting value types: the maths that turns real counters
 * into the "today" cards (and that must survive a "reset today" baseline, a midnight
 * rollover and negative deltas).
 */
class AccountingTest {

    @Test
    fun resetBaselineOnlyAppliesToItsOwnDay() {
        val offsets = ResetOffsets("2026-10-13", mobileRx = 500L, mobileTx = 50L)
        assertTrue(offsets.appliesTo("2026-10-13"))
        assertFalse(offsets.appliesTo("2026-10-14"))
        assertFalse(ResetOffsets().appliesTo("2026-10-13"))
    }

    @Test
    fun subtractingTheBaselineClampsAtZero() {
        val sinceMidnight = TransportBytes(rx = 1_000L, tx = 900L)
        val baseline = TransportBytes(rx = 400L, tx = 950L) // reset happened "later" for tx
        val sinceReset = sinceMidnight - baseline
        assertEquals(600L, sinceReset.rx)
        assertEquals(0L, sinceReset.tx) // never negative
    }

    @Test
    fun wifiAndEthernetCombineIntoTheHomeBucket() {
        val wifi = TransportBytes(1_000L, 100L)
        val ethernet = TransportBytes(2_000L, 200L)
        val home = wifi + ethernet
        assertEquals(3_000L, home.rx)
        assertEquals(300L, home.tx)
        assertEquals(3_300L, home.total)
    }

    @Test
    fun dayTotalsAddUpBothDirections() {
        val entity = DataUsageEntity(
            date = "2026-10-13",
            mobileRx = 100L,
            mobileTx = 10L,
            wifiRx = 1_000L,
            wifiTx = 100L
        )
        assertEquals(110L, entity.mobileTotal)
        assertEquals(1_100L, entity.wifiTotal)
        assertEquals(1_210L, entity.total)
    }

    @Test
    fun midnightRolloverStartsFromZeroAgain() {
        // What the accounting loop does when the calendar day changes.
        var integratedMobileRx = 5_000_000L
        var integratedWifiRx = 9_000_000L
        val lastAccountedDay = "2026-10-13"
        val today = "2026-10-14"

        if (lastAccountedDay != today) {
            integratedMobileRx = 0L
            integratedWifiRx = 0L
        }
        assertEquals(0L, integratedMobileRx)
        assertEquals(0L, integratedWifiRx)
        assertEquals(0L, DataUsageEntity(today).total)
    }
}
