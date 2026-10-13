package com.example.services

/**
 * Pure, Android-free decision core for the "is the counter source we are reading still
 * alive?" question — extracted from the 500 ms read loop so it can be unit-tested.
 *
 * Why this exists: the meter reads the device-wide byte counters from two possible
 * sources ([CounterSource.TRAFFIC_STATS] and [CounterSource.PROC_NET_DEV]), and some OEM
 * ROMs freeze or blank one of them. The old implementation compared *absolute totals
 * across two different counters*, which made the ticker flap between sources every few
 * seconds and skip/duplicate published ticks (the original "stuttering speed" bug).
 *
 * The rule is now conservative and evidence-based, in two steps:
 *
 *  1. [shouldProbe] — the active source looks sick (unsupported/zero, or N reads without
 *     any movement) **and** enough reads have passed since the last probe. Probing costs
 *     an extra file read, so it must not happen on every tick.
 *  2. [decide] — with the other source's evidence in hand, switch **only** on positive
 *     evidence: either it demonstrably progressed between two of *its own* readings, or
 *     the active source is dead while the other one has real bytes.
 */
object SourceHealth {

    /** What the read loop knows about the active source before it probes. */
    data class Symptoms(
        /** `false` when the active source reports an unsupported or zero counter. */
        val activeUsable: Boolean,
        /** Consecutive reads of the active source without any byte movement. */
        val stagnantReads: Int,
        /** Reads since the last cross-source probe. */
        val readsSinceProbe: Int
    )

    /** What one probe of the *other* source told us. */
    data class Evidence(
        /** `false` when the active source reports an unsupported or zero counter. */
        val activeUsable: Boolean,
        /** `false` when the other source reports an unsupported or zero counter. */
        val otherUsable: Boolean,
        /** Bytes reported by the other source right now (0 ⇒ nothing to switch to). */
        val otherTotal: Long,
        /** Whether the other source moved between two of its own consecutive readings. */
        val otherProgressed: Boolean
    )

    /** Outcome of [decide]. */
    enum class Decision {
        /** Keep reading the active source. */
        KEEP,
        /** Positive evidence from the other source: switch and re-anchor the engine. */
        SWITCH
    }

    /** Consecutive reads without movement before the other source is considered. */
    const val STAGNANT_READS = 6

    /** Minimum reads between two cross-source probes (keeps the loop cheap). */
    const val PROBE_EVERY_READS = 4

    /** True when the active source looks sick enough to spend a probe on. */
    fun shouldProbe(symptoms: Symptoms): Boolean {
        val activeSick = !symptoms.activeUsable || symptoms.stagnantReads >= STAGNANT_READS
        return activeSick && symptoms.readsSinceProbe >= PROBE_EVERY_READS
    }

    /**
     * Positive-evidence-only switch: the meter never leaves a quiet source, only one that
     * is demonstrably broken — and only for a source that demonstrably works.
     */
    fun decide(evidence: Evidence): Decision {
        val otherAlive = evidence.otherProgressed ||
            (!evidence.activeUsable && evidence.otherUsable && evidence.otherTotal > 0L)
        return if (otherAlive) Decision.SWITCH else Decision.KEEP
    }

    /**
     * Whether a single reading is worth integrating at all.
     *
     * A source that reports `UNSUPPORTED` (-1) or a hard 0 total is indistinguishable
     * from "the kernel gave us nothing", and integrating it would publish a fake drop.
     */
    fun isUsable(sample: SpeedSample): Boolean = sample.isSupported && sample.total > 0L

    /**
     * Whether the counter moved between two readings of the *same* source.
     *
     * Comparing two readings of one source is the only legitimate liveness check —
     * comparing totals across two different sources is what caused the old flapping.
     */
    fun progressed(newer: SpeedSample, older: SpeedSample): Boolean = newer.movedFrom(older)
}
