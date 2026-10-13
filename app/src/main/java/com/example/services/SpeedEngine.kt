package com.example.services

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

/**
 * Which network the device is currently using.
 *
 * [ETHERNET] and [VPN] are bucketed with Wi-Fi for the daily usage split, because an
 * app without `ACCESS_WIFI_STATE`/`READ_PHONE_STATE` cannot attribute tunnelled or
 * wired traffic to a specific radio.
 */
enum class Transport {
    WIFI,
    MOBILE,
    ETHERNET,
    VPN,
    NONE;

    /** True for transports that count towards the "Wi-Fi" bucket of the daily split. */
    val isWifiBucket: Boolean get() = this == WIFI || this == ETHERNET || this == VPN
}

/** Bytes transferred in both directions. Immutable value type. */
data class TransferBytes(val rx: Long = 0L, val tx: Long = 0L) {
    val total: Long get() = rx + tx
    val isZero: Boolean get() = rx == 0L && tx == 0L

    operator fun plus(other: TransferBytes) = TransferBytes(rx + other.rx, tx + other.tx)

    operator fun minus(other: TransferBytes) = TransferBytes(rx - other.rx, tx - other.tx)
}

/**
 * One reading of the device-wide byte counters.
 *
 * `rx` = bytes received (download), `tx` = bytes sent (upload). The two directions are
 * **independent** values that always come from a single coherent counter read, so a
 * download-heavy burst can never be mirrored into the upload number (and vice versa).
 *
 * [rxSupported]/[txSupported] are false when the OS reports the counter as
 * `TrafficStats.UNSUPPORTED` — the engine then treats the direction as unmeasurable
 * instead of silently publishing a fabricated `0`.
 */
data class SpeedSample(
    val rx: Long,
    val tx: Long,
    val atMs: Long,
    val rxSupported: Boolean = true,
    val txSupported: Boolean = true
) {
    val total: Long get() = rx + tx
    val isZero: Boolean get() = rx <= 0L && tx <= 0L
    val isSupported: Boolean get() = rxSupported || txSupported

    fun movedFrom(other: SpeedSample): Boolean = rx > other.rx || tx > other.tx
}

/**
 * Everything the UI, the notification and the diagnostics panel need for one tick.
 *
 * Rates are **bytes per second**, measured over the real elapsed window (never assumed
 * to be exactly one second). `paused == true` means the measurement window was too long
 * (the process was frozen by the OS), so the rates are deliberately `0` rather than an
 * averaged fake — exactly how dedicated speed meters behave after a suspension.
 */
data class LiveSpeed(
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    /** Smoothed (EWMA) rate — used for the status-bar number so it doesn't strobe. */
    val steadyDownloadBps: Long = 0L,
    val steadyUploadBps: Long = 0L,
    /** Average of the published raw rates over the last [SpeedEngine.averageWindowMs]. */
    val averageDownloadBps: Long = 0L,
    val averageUploadBps: Long = 0L,
    val peakDownloadBps: Long = 0L,
    val peakUploadBps: Long = 0L,
    val wifiDownloadBps: Long = 0L,
    val wifiUploadBps: Long = 0L,
    val mobileDownloadBps: Long = 0L,
    val mobileUploadBps: Long = 0L,
    val sessionRxBytes: Long = 0L,
    val sessionTxBytes: Long = 0L,
    val sessionDurationMs: Long = 0L,
    val transport: Transport = Transport.NONE,
    val downloadHistory: List<Long> = emptyList(),
    val uploadHistory: List<Long> = emptyList(),
    val windowMs: Long = 0L,
    val paused: Boolean = false,
    val samplesTaken: Long = 0L
) {
    val sessionBytes: Long get() = sessionRxBytes + sessionTxBytes
    val isIdle: Boolean get() = downloadBps <= 0L && uploadBps <= 0L
}

/**
 * The measurement core of the app — **pure Kotlin, zero Android dependencies**, so the
 * whole speed/accounting maths is unit-testable.
 *
 * How it is used (see [SpeedMeterService]):
 *
 * ```
 * engine.seed(firstSample)                       // service start / counter-source switch
 * val published = engine.onSample(nextSample)     // every read (500 ms)
 * if (published != null) ui.value = published     // publish is due (~1 Hz)
 * ```
 *
 * What it guarantees:
 *  - **Independent directions** — download and upload are computed from `rx`/`tx`
 *    deltas of the *same* read; they can never be swapped or mirrored.
 *  - **Real window** — every rate is `bytes / (measured elapsed ms)`, never
 *    `bytes / 1000`.
 *  - **No fake spikes** — a counter that goes *backwards* (interface reset, source
 *    switch, reboot) or a window longer than [maxValidWindowMs] (process frozen by the
 *    OS) publishes `0` instead of a bogus avalanche/average.
 *  - **Real byte accounting** — bytes that moved during a long window are still counted
 *    into the session totals and per-transport totals (they really crossed the wire),
 *    only the *rate* is refused.
 *  - **Session + rolling stats** — session totals, session peaks, a 60 s average and a
 *    60-sample history for the sparkline.
 */
class SpeedEngine(
    private val publishIntervalMs: Long = 1_000L,
    private val maxValidWindowMs: Long = 3_000L,
    private val smoothing: Double = 0.5,
    private val averageWindowMs: Long = 60_000L,
    private val historySize: Int = 60
) {

    private var anchor: SpeedSample? = null

    // Window accumulated since the previous publish.
    private var windowAnchorMs = 0L
    private var lastPublishMs = 0L
    private var pendingRx = 0L
    private var pendingTx = 0L
    private var windowWifiRx = 0L
    private var windowWifiTx = 0L
    private var windowMobileRx = 0L
    private var windowMobileTx = 0L

    // Published state.
    private var transport = Transport.NONE
    private var windowMs = 0L
    private var paused = false
    private var samplesTaken = 0L
    private var dlRate = 0L
    private var ulRate = 0L
    private var steadyDl = 0.0
    private var steadyUl = 0.0
    private var steadyInitialized = false
    private var wifiDlRate = 0L
    private var wifiUlRate = 0L
    private var mobileDlRate = 0L
    private var mobileUlRate = 0L

    // Session state.
    private var sessionRx = 0L
    private var sessionTx = 0L
    private var sessionStartMs = 0L
    private var peakDl = 0L
    private var peakUl = 0L

    /**
     * Cumulative per-transport bytes. The 15 s accounting loop reads a copy of this from
     * another coroutine while the read loop keeps writing, so it must be a concurrent map
     * (single writer, weakly-consistent iteration → no `ConcurrentModificationException`).
     */
    private val attributedTotal = ConcurrentHashMap<Transport, TransferBytes>()

    private val downloadHistory = ArrayDeque<Long>()
    private val uploadHistory = ArrayDeque<Long>()
    private val averageSamples = ArrayDeque<AverageSample>()

    @Volatile
    private var current = LiveSpeed()

    private data class AverageSample(val atMs: Long, val dl: Long, val ul: Long)

    /**
     * Re-anchors the engine to a fresh counter reading without touching the session
     * totals. Used at service start and whenever the counter source is switched, so the
     * first delta after a switch is measured *within* the new source.
     */
    fun seed(sample: SpeedSample) {
        anchor = sample
        windowAnchorMs = sample.atMs
        lastPublishMs = sample.atMs
        if (sessionStartMs == 0L) sessionStartMs = sample.atMs
        pendingRx = 0L
        pendingTx = 0L
        windowWifiRx = 0L
        windowWifiTx = 0L
        windowMobileRx = 0L
        windowMobileTx = 0L
        paused = false
    }

    /** Full session reset (service restart, or the user's "reset session" action). */
    fun resetSession(sample: SpeedSample) {
        sessionRx = 0L
        sessionTx = 0L
        sessionStartMs = sample.atMs
        peakDl = 0L
        peakUl = 0L
        steadyDl = 0.0
        steadyUl = 0.0
        steadyInitialized = false
        dlRate = 0L
        ulRate = 0L
        attributedTotal.clear()
        downloadHistory.clear()
        uploadHistory.clear()
        averageSamples.clear()
        sessionStartMs = sample.atMs
        seed(sample)
    }

    /**
     * Seeds the *smoothed* value from the last persisted measurement so the status-bar
     * number is meaningful the instant the service starts (no 0 flash). The first real
     * publish overwrites it, so a stale value can never stick around.
     */
    fun restoreLastKnown(downloadBps: Long, uploadBps: Long) {
        if (steadyInitialized) return
        if (downloadBps <= 0L && uploadBps <= 0L) return
        steadyDl = downloadBps.toDouble()
        steadyUl = uploadBps.toDouble()
        publishSnapshot()
    }

    fun setTransport(value: Transport) {
        if (value == transport) return
        val previous = transport
        transport = value
        // Rates belong to the transport they were measured on: switching zeroes them
        // instead of letting Wi-Fi speed "stick" after moving to mobile data.
        when {
            previous.isWifiBucket -> {
                wifiDlRate = 0L
                wifiUlRate = 0L
            }
            previous == Transport.MOBILE -> {
                mobileDlRate = 0L
                mobileUlRate = 0L
            }
        }
        // The window opened on the old transport keeps its bytes for the daily split,
        // but its rates are no longer attributable to the new one.
        windowWifiRx = 0L
        windowWifiTx = 0L
        windowMobileRx = 0L
        windowMobileTx = 0L
        publishSnapshot()
    }

    fun transport(): Transport = transport

    /** Cumulative bytes attributed per transport since the last [resetSession]. */
    fun attributedTotals(): Map<Transport, TransferBytes> = HashMap(attributedTotal)

    /** Last published state (used for the notification right after a service start). */
    fun snapshot(): LiveSpeed = current

    /**
     * Feeds one counter reading. Returns a new [LiveSpeed] when a publish is due
     * (~[publishIntervalMs]), otherwise `null`.
     */
    fun onSample(sample: SpeedSample): LiveSpeed? {
        val previous = anchor
        if (previous == null) {
            seed(sample)
            current = current.copy(transport = transport)
            return null
        }
        val elapsed = sample.atMs - previous.atMs
        if (elapsed <= 0L) {
            // Same/duplicated timestamp — keep the newest values but do not integrate.
            anchor = sample
            return null
        }
        anchor = sample
        samplesTaken++

        val rxMeasurable = sample.rxSupported && previous.rxSupported
        val txMeasurable = sample.txSupported && previous.txSupported
        val deltaRx = if (rxMeasurable) sample.rx - previous.rx else 0L
        val deltaTx = if (txMeasurable) sample.tx - previous.tx else 0L

        if (deltaRx < 0L || deltaTx < 0L) {
            // Counter went backwards: interface reset, source switch or device reboot.
            // Re-anchor silently — publishing here would be a fabricated spike.
            pendingRx = 0L
            pendingTx = 0L
            windowAnchorMs = sample.atMs
            windowWifiRx = 0L
            windowWifiTx = 0L
            windowMobileRx = 0L
            windowMobileTx = 0L
            return null
        }

        // Real bytes always count, even if the window turns out to be unusable.
        sessionRx += deltaRx
        sessionTx += deltaTx
        attribute(deltaRx, deltaTx)

        pendingRx += deltaRx
        pendingTx += deltaTx

        if (sample.atMs - lastPublishMs < publishIntervalMs) return null
        return publish(sample.atMs)
    }

    private fun attribute(deltaRx: Long, deltaTx: Long) {
        if (deltaRx == 0L && deltaTx == 0L) return
        val bucket = transport
        // Bytes moved while no transport was active (radio just dropped) still count
        // towards the session total, they just cannot be split into the daily buckets.
        if (bucket == Transport.NONE) return
        attributedTotal[bucket] = (attributedTotal[bucket] ?: TransferBytes()) +
            TransferBytes(deltaRx, deltaTx)
        if (bucket.isWifiBucket) {
            windowWifiRx += deltaRx
            windowWifiTx += deltaTx
        } else if (bucket == Transport.MOBILE) {
            windowMobileRx += deltaRx
            windowMobileTx += deltaTx
        }
    }

    private fun publish(nowMs: Long): LiveSpeed {
        val measuredWindow = nowMs - windowAnchorMs
        val usable = measuredWindow in 1..maxValidWindowMs
        paused = !usable
        windowMs = measuredWindow

        if (usable) {
            val dl = bytesPerSecond(pendingRx, measuredWindow)
            val ul = bytesPerSecond(pendingTx, measuredWindow)
            dlRate = dl
            ulRate = ul

            steadyDl = if (!steadyInitialized) dl.toDouble() else smoothing * dl + (1.0 - smoothing) * steadyDl
            steadyUl = if (!steadyInitialized) ul.toDouble() else smoothing * ul + (1.0 - smoothing) * steadyUl
            steadyInitialized = true

            if (dl > peakDl) peakDl = dl
            if (ul > peakUl) peakUl = ul

            pushHistory(downloadHistory, dl)
            pushHistory(uploadHistory, ul)
            recordAverage(nowMs, dl, ul)

            wifiDlRate = bytesPerSecond(windowWifiRx, measuredWindow)
            wifiUlRate = bytesPerSecond(windowWifiTx, measuredWindow)
            mobileDlRate = bytesPerSecond(windowMobileRx, measuredWindow)
            mobileUlRate = bytesPerSecond(windowMobileTx, measuredWindow)
        } else {
            // Suspended process: the rates are unknown, so they are 0 — and nothing is
            // written into the history/average so the charts stay truthful.
            dlRate = 0L
            ulRate = 0L
            resetWindowRates()
        }

        pendingRx = 0L
        pendingTx = 0L
        windowWifiRx = 0L
        windowWifiTx = 0L
        windowMobileRx = 0L
        windowMobileTx = 0L
        windowAnchorMs = nowMs
        lastPublishMs = nowMs

        publishSnapshot()
        return current
    }

    private fun resetWindowRates() {
        wifiDlRate = 0L
        wifiUlRate = 0L
        mobileDlRate = 0L
        mobileUlRate = 0L
    }

    private fun publishSnapshot() {
        current = LiveSpeed(
            downloadBps = dlRate,
            uploadBps = ulRate,
            steadyDownloadBps = steadyDl.roundToLong(),
            steadyUploadBps = steadyUl.roundToLong(),
            averageDownloadBps = averageOf { it.dl },
            averageUploadBps = averageOf { it.ul },
            peakDownloadBps = peakDl,
            peakUploadBps = peakUl,
            wifiDownloadBps = wifiDlRate,
            wifiUploadBps = wifiUlRate,
            mobileDownloadBps = mobileDlRate,
            mobileUploadBps = mobileUlRate,
            sessionRxBytes = sessionRx,
            sessionTxBytes = sessionTx,
            sessionDurationMs = if (sessionStartMs == 0L) 0L else (currentTimeStamp() - sessionStartMs),
            transport = transport,
            downloadHistory = downloadHistory.toList(),
            uploadHistory = uploadHistory.toList(),
            windowMs = windowMs,
            paused = paused,
            samplesTaken = samplesTaken
        )
    }

    /** Overridable clock: the engine needs the timestamp of the *last* sample. */
    private fun currentTimeStamp(): Long = anchor?.atMs ?: 0L

    private fun pushHistory(history: ArrayDeque<Long>, value: Long) {
        history.addLast(value)
        while (history.size > historySize) history.removeFirst()
    }

    private fun recordAverage(nowMs: Long, dl: Long, ul: Long) {
        averageSamples.addLast(AverageSample(nowMs, dl, ul))
        while (averageSamples.isNotEmpty() && nowMs - averageSamples.first().atMs > averageWindowMs) {
            averageSamples.removeFirst()
        }
    }

    private fun averageOf(selector: (AverageSample) -> Long): Long {
        if (averageSamples.isEmpty()) return 0L
        var sum = 0.0
        for (sample in averageSamples) sum += selector(sample)
        return (sum / averageSamples.size).roundToLong()
    }

    private fun bytesPerSecond(bytes: Long, windowMs: Long): Long {
        if (bytes <= 0L || windowMs <= 0L) return 0L
        return (bytes.toDouble() * 1000.0 / windowMs.toDouble()).roundToLong()
    }
}
