package com.example

import com.example.services.ProbeResult
import com.example.services.SourceEvaluation
import com.example.services.SourceHealth
import com.example.services.SpeedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counter-source switch rule, tested without a device.
 *
 * This rule matters because getting it wrong produced two real bugs: the meter flapping
 * between `TrafficStats` and `/proc/net/dev` every few seconds (duplicate/stuttering
 * publishes), and a dead counter being read forever (a frozen speed).
 */
class SourceHealthTest {

    private fun sample(rx: Long, tx: Long = 0L, atMs: Long = 0L) = SpeedSample(rx, tx, atMs)

    @Test
    fun aMovingSourceIsNeverProbedOrSwitched() {
        val health = SourceHealth(stagnantReadsTrigger = 3, probeEveryReads = 1)
        var previous = sample(1_000L)
        var probes = 0

        repeat(10) { step ->
            val reading = sample(1_000L + (step + 1) * 500L)
            val evaluation = health.onRead(reading, previous) {
                probes++
                ProbeResult(sample(999_999L), null)
            }
            assertFalse(evaluation.switchToOther)
            assertFalse("a healthy source must not trigger probes", evaluation.probeDue)
            previous = reading
        }
        assertEquals(0, probes)
        assertEquals(0, health.stagnantReads)
    }

    @Test
    fun aStagnantSourceIsProbedButNotSwitchedToADeadPeer() {
        val health = SourceHealth(stagnantReadsTrigger = 3, probeEveryReads = 1)
        val frozen = sample(5_000L)

        var last: SourceEvaluation? = null
        repeat(3) {
            last = health.onRead(frozen, frozen) { ProbeResult(sample(7_000L), sample(7_000L)) }
        }
        assertTrue("the trigger must ask for a peer read", last!!.probeDue)
        assertFalse("a peer that does not move is not a better source", last!!.switchToOther)
        assertEquals(3, health.stagnantReads)
    }

    @Test
    fun aStagnantSourceSwitchesToAPeerThatIsMoving() {
        val health = SourceHealth(stagnantReadsTrigger = 3, probeEveryReads = 1)
        val frozen = sample(5_000L)

        var evaluation = health.onRead(frozen, frozen) // stagnant 1
        evaluation = health.onRead(frozen, frozen)     // stagnant 2
        assertFalse(evaluation.probeDue)

        evaluation = health.onRead(frozen, frozen) {   // stagnant 3 → probe + switch
            ProbeResult(reading = sample(90_000L), shadow = sample(80_000L))
        }
        assertTrue(evaluation.probeDue)
        assertTrue("the peer moved, so it is alive", evaluation.switchToOther)
        assertEquals("stagnant", evaluation.reason)

        health.onSwitched()
        assertEquals(0, health.stagnantReads)
    }

    @Test
    fun anUnsupportedDirectionSwitchesToAPeerReportingRealBytes() {
        val health = SourceHealth(stagnantReadsTrigger = 99, probeEveryReads = 1)
        val unmeasurable = SpeedSample(0L, 0L, 0L, rxSupported = false, txSupported = false)

        val evaluation = health.onRead(unmeasurable, unmeasurable) {
            ProbeResult(reading = sample(1_000_000L), shadow = sample(1_000_000L))
        }
        assertTrue(evaluation.probeDue)
        assertTrue("UNSUPPORTED counters are not zero bytes", evaluation.switchToOther)
        assertEquals("unsupported", evaluation.reason)
    }

    @Test
    fun probesAreRateLimitedSoTheHotPathStaysCheap() {
        val health = SourceHealth(stagnantReadsTrigger = 1, probeEveryReads = 4)
        val frozen = sample(2_000L)
        var probes = 0

        repeat(12) {
            health.onRead(frozen, frozen) {
                probes++
                ProbeResult(reading = sample(50_000L), shadow = sample(40_000L))
            }
        }
        // 12 reads with a one-in-four gate → 3 probes, not 12 (`/proc/net/dev` is not read
        // on every tick while the rule is merely suspicious).
        assertEquals(3, probes)
    }

    @Test
    fun aPeerWithoutAShadowIsNeverJudgedAlive() {
        val health = SourceHealth(stagnantReadsTrigger = 2, probeEveryReads = 1)
        val frozen = sample(4_000L)

        health.onRead(frozen, frozen)
        val evaluation = health.onRead(frozen, frozen, probe = null)
        assertFalse(evaluation.switchToOther)
        assertTrue(evaluation.probeDue)

        // No shadow yet (e.g. right after a switch) → a move cannot be proven.
        val noShadow = health.onRead(frozen, frozen) {
            ProbeResult(reading = sample(80_000L), shadow = null)
        }
        assertFalse(noShadow.switchToOther)
    }
}
