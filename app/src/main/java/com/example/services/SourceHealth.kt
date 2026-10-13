package com.example.services

/** One probe of the *other* counter source: the fresh reading plus the previous shadow. */
data class ProbeResult(val reading: SpeedSample, val shadow: SpeedSample?)

/** What the read loop should do after a counter reading. */
data class SourceEvaluation(
    /** True when the reading actually moved relative to the previous one. */
    val moved: Boolean,
    /** True when the other counter source was probed on this tick. */
    val probeDue: Boolean,
    /** True when the other source is proven alive and the loop should switch to it. */
    val switchToOther: Boolean,
    /** Why the probe happened ("stagnant" / "unsupported"), for diagnostics and tests. */
    val reason: String? = null
)

/**
 * Decides when the meter should abandon a counter source and read the other one.
 *
 * Extracted from `SpeedMeterService` so the rule is **pure and unit-tested** instead of
 * living inside a 500 ms loop:
 *
 *  - a source that does not move for [stagnantReadsTrigger] consecutive reads is
 *    suspicious (some OEM ROMs lock the `TrafficStats` totals), so the other source is
 *    probed;
 *  - a direction the OS reports as `UNSUPPORTED` triggers the same probe;
 *  - probes are rate-limited to one every [probeEveryReads] reads, so the hot path never
 *    performs an extra `/proc/net/dev` read on every tick;
 *  - a switch happens **only** when the other source moved since the previous probe or
 *    when the current one is unsupported while the other reports real bytes. Comparing
 *    absolute totals across two different counters is what used to make the old
 *    implementation flap between sources every few seconds.
 *
 * The class holds no Android dependency and no clock: it only counts reads, and the probe
 * itself is supplied by the caller (so tests can inject synthetic samples).
 */
class SourceHealth(
    private val stagnantReadsTrigger: Int = 6,
    private val probeEveryReads: Int = 4
) {

    var stagnantReads: Int = 0
        private set

    private var readsSinceProbe = 0

    /**
     * Feeds one reading of the active source. [probe] is invoked **only** when a probe is
     * due, and must return the other source's fresh reading plus the shadow it is compared
     * against (`null` when there is no shadow yet, e.g. right after a switch).
     */
    fun onRead(
        reading: SpeedSample,
        previous: SpeedSample,
        probe: (() -> ProbeResult?)? = null
    ): SourceEvaluation {
        val moved = reading.movedFrom(previous)
        stagnantReads = if (moved) 0 else stagnantReads + 1

        val unsupported = !reading.isSupported || reading.total <= 0L
        readsSinceProbe++
        val due = (unsupported || stagnantReads >= stagnantReadsTrigger) &&
            readsSinceProbe >= probeEveryReads
        if (!due) {
            return SourceEvaluation(moved = moved, probeDue = false, switchToOther = false)
        }

        readsSinceProbe = 0
        val reason = if (unsupported) "unsupported" else "stagnant"
        val probed = probe?.invoke()
            ?: return SourceEvaluation(moved = moved, probeDue = true, switchToOther = false, reason = reason)

        val otherMoving = probed.shadow != null && probed.reading.movedFrom(probed.shadow)
        val otherAlive = probed.reading.total > 0L && probed.reading.isSupported
        val switch = otherMoving || (unsupported && otherAlive)
        return SourceEvaluation(moved = moved, probeDue = true, switchToOther = switch, reason = reason)
    }

    /** Called after the loop switched sources: the health counters start from zero. */
    fun onSwitched() {
        stagnantReads = 0
        readsSinceProbe = 0
    }
}
