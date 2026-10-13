package com.example

import com.example.services.CounterSource
import com.example.services.SourceHealth
import com.example.services.SourceHealth.Decision
import com.example.services.SourceHealth.Symptoms
import com.example.services.SourceHealth.Evidence
import com.example.services.SpeedSample
import com.example.services.TrafficCounterReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counter-source switch decision, extracted from the 500 ms read loop.
 *
 * These tests exist because of a real bug: the meter used to compare *absolute totals
 * across two different counters*, which made it flap between `TrafficStats` and
 * `/proc/net/dev` every few seconds and publish stuttering, duplicated speeds.
 */
class SourceHealthTest {

    private fun sample(rx: Long, tx: Long = 0L, atMs: Long = 0L) = SpeedSample(rx, tx, atMs)

    // ------------------------------------------------------------ shouldProbe

    @Test
    fun aHealthySourceIsNeverProbed() {
        val decision = SourceHealth.shouldProbe(
            Symptoms(activeUsable = true, stagnantReads = 0, readsSinceProbe = 99)
        )
        assertFalse(decision)
    }

    @Test
    fun aDeadSourceIsProbed() {
        assertTrue(
            SourceHealth.shouldProbe(
                Symptoms(activeUsable = false, stagnantReads = 0, readsSinceProbe = 4)
            )
        )
    }

    @Test
    fun aStagnantSourceIsProbedOnlyAfterEnoughReads() {
        val stagnant = SourceHealth.STAGNANT_READS
        assertTrue(
            SourceHealth.shouldProbe(
                Symptoms(activeUsable = true, stagnantReads = stagnant, readsSinceProbe = 4)
            )
        )
        assertFalse(
            "one read short of stagnation → not sick yet",
            SourceHealth.shouldProbe(
                Symptoms(activeUsable = true, stagnantReads = stagnant - 1, readsSinceProbe = 4)
            )
        )
    }

    @Test
    fun probingIsThrottledEvenWhenTheSourceIsSick() {
        assertFalse(
            "a probe costs a file read — it must not run on every tick",
            SourceHealth.shouldProbe(
                Symptoms(activeUsable = false, stagnantReads = 99, readsSinceProbe = 1)
            )
        )
    }

    // --------------------------------------------------------------- decide

    @Test
    fun theOtherSourceProgressingIsPositiveEvidence() {
        assertEquals(
            Decision.SWITCH,
            SourceHealth.decide(
                Evidence(
                    activeUsable = true,
                    otherUsable = true,
                    otherTotal = 5_000L,
                    otherProgressed = true
                )
            )
        )
    }

    @Test
    fun aQuietActiveSourceAloneIsNeverEnoughToSwitch() {
        assertEquals(
            Decision.KEEP,
            SourceHealth.decide(
                Evidence(
                    activeUsable = true,
                    otherUsable = true,
                    otherTotal = 5_000L,
                    otherProgressed = false
                )
            )
        )
    }

    @Test
    fun aDeadActiveSourceSwitchesToAnySourceWithBytes() {
        assertEquals(
            Decision.SWITCH,
            SourceHealth.decide(
                Evidence(
                    activeUsable = false,
                    otherUsable = true,
                    otherTotal = 1L,
                    otherProgressed = false
                )
            )
        )
    }

    @Test
    fun anEmptyOtherSourceIsNeverSelected() {
        assertEquals(
            Decision.KEEP,
            SourceHealth.decide(
                Evidence(
                    activeUsable = false,
                    otherUsable = true,
                    otherTotal = 0L,
                    otherProgressed = false
                )
            )
        )
    }

    @Test
    fun anUnsupportedOtherSourceIsNeverSelected() {
        assertEquals(
            Decision.KEEP,
            SourceHealth.decide(
                Evidence(
                    activeUsable = false,
                    otherUsable = false,
                    otherTotal = 5_000L,
                    otherProgressed = false
                )
            )
        )
    }

    // -------------------------------------------------------------- helpers

    @Test
    fun unsupportedCountersAreNotUsable() {
        assertFalse(SourceHealth.isUsable(SpeedSample(0L, 0L, 0L, rxSupported = false, txSupported = true)))
        assertFalse(SourceHealth.isUsable(sample(0L, 0L)))
        assertTrue(SourceHealth.isUsable(sample(1L, 0L)))
        assertTrue(SourceHealth.isUsable(sample(0L, 1L)))
    }

    @Test
    fun progressMeansEitherDirectionMoved() {
        assertTrue(SourceHealth.progressed(sample(200L, 0L), sample(100L, 0L)))
        assertTrue(SourceHealth.progressed(sample(0L, 200L), sample(0L, 100L)))
        assertFalse(SourceHealth.progressed(sample(100L, 100L), sample(100L, 100L)))
    }

    @Test
    fun theTwoSourcesAreEachOthersCounterpart() {
        assertEquals(
            CounterSource.PROC_NET_DEV,
            TrafficCounterReader.other(CounterSource.TRAFFIC_STATS)
        )
        assertEquals(
            CounterSource.TRAFFIC_STATS,
            TrafficCounterReader.other(CounterSource.PROC_NET_DEV)
        )
    }
}
