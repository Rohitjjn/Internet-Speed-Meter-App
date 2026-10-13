package com.example.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Round-trip statistics of the latency probe (milliseconds). */
data class LatencyResult(
    val minMs: Long,
    val averageMs: Long,
    val jitterMs: Long,
    val samples: Int
)

/** Result of one throughput phase. [bitsPerSecond] is application-level throughput. */
data class ThroughputResult(
    val bitsPerSecond: Double,
    val bytes: Long,
    val durationMs: Long,
    /** How many parallel streams actually carried data (see the ramp-up below). */
    val streams: Int
) {
    val megabytesPerSecond: Double get() = bytes / 1024.0 / 1024.0 / (durationMs / 1000.0)
}

/**
 * One public speed-test endpoint.
 *
 * The app ships **no SDK and no analytics**: every server here is a plain HTTPS endpoint
 * the app talks to with `HttpURLConnection`, and only when the user taps "Start test".
 * A server that cannot take an upload simply declares `uploadUrl = null` — the test then
 * reports upload as "not measured" instead of inventing a number.
 */
data class SpeedTestServer(
    val id: String,
    /** Human-readable name shown in the UI and in the share sheet. */
    val name: String,
    val host: String,
    /** Smallest possible request — used for the round-trip probe. */
    val latencyUrl: String,
    /** Download URL; `{bytes}` is replaced by the requested payload size. */
    val downloadTemplate: String,
    /** Upload endpoint, or `null` when this server cannot take an upload. */
    val uploadUrl: String? = null
) {
    fun downloadUrl(bytes: Long): String = downloadTemplate.replace("{bytes}", bytes.toString())

    val supportsUpload: Boolean get() = uploadUrl != null
}

/**
 * The **active** measurement: this is the part that answers "what is my actual internet
 * speed right now?" the way dedicated speed-test apps do, instead of only observing the
 * traffic the device happens to be generating.
 *
 * How it measures (all of it real, none of it simulated):
 *
 *  - **Latency / jitter** — one throw-away warm-up request (pays for DNS + TCP + TLS),
 *    then measured round trips; jitter = mean absolute difference of consecutive samples.
 *  - **Download** — several parallel `GET` streams, counting the bytes that actually
 *    arrive, for a fixed time budget.
 *  - **Upload** — several parallel chunked `POST` streams, counting the bytes the socket
 *    accepted, then reading the server's response so a rejected upload cannot be reported
 *    as a throughput number.
 *
 * Throughput is reported as `bits × 8 / seconds` (decimal Mbps) — the unit ISPs advertise.
 *
 * Two resilience features that matter in the real world:
 *
 *  - **More than one server.** `speed.cloudflare.com` is blocked by some corporate DNS
 *    servers and a few ISPs. [servers] is tried in order and the test fails over to the
 *    next candidate that answers a round trip.
 *  - **Ramp-up instead of a fixed stream count.** 4 streams are right for a fast line but
 *    add connection-setup noise on a slow one, so the test opens [INITIAL_STREAMS] first
 *    and only adds the rest when the line proves it can use them.
 *
 * Every phase also takes an [Abort] signal: if the transport changes mid-test (Wi-Fi →
 * mobile data) the caller pulls it and the phases unwind instead of billing the user's
 * mobile plan for a download burst.
 */
object SpeedTestEngine {

    /** Abort signal handed to every phase. Pulled by the caller (transport change). */
    fun interface Abort {
        fun isAborted(): Boolean
    }

    val CLOUDFLARE = SpeedTestServer(
        id = "CLOUDFLARE",
        name = "Cloudflare",
        host = "speed.cloudflare.com",
        latencyUrl = "https://speed.cloudflare.com/__down?bytes=0",
        downloadTemplate = "https://speed.cloudflare.com/__down?bytes={bytes}",
        uploadUrl = "https://speed.cloudflare.com/__up"
    )

    /** Download/latency fallback (Germany) — used when Cloudflare is unreachable. */
    val HETZNER = SpeedTestServer(
        id = "HETZNER",
        name = "Hetzner · Nuremberg",
        host = "nbg1-speed.hetzner.com",
        latencyUrl = "https://nbg1-speed.hetzner.com/1MB.bin",
        downloadTemplate = "https://nbg1-speed.hetzner.com/100MB.bin",
        uploadUrl = null
    )

    /** Download/latency fallback (France) — second fallback. */
    val OVH = SpeedTestServer(
        id = "OVH",
        name = "OVH · Gravelines",
        host = "proof.ovh.net",
        latencyUrl = "https://proof.ovh.net/files/1Mb.dat",
        downloadTemplate = "https://proof.ovh.net/files/100Mb.dat",
        uploadUrl = null
    )

    val servers: List<SpeedTestServer> = listOf(CLOUDFLARE, HETZNER, OVH)

    /** Host of the default server (kept for the UI copy that names the endpoint). */
    const val SERVER_NAME = "speed.cloudflare.com"

    /**
     * Marker message thrown when the caller pulls the abort signal (the transport changed
     * mid-test). The engine has no Context, so the UI maps this marker to a translated
     * string — it must never be shown raw.
     */
    const val ABORT_MESSAGE = "abort:transport-changed"

    /** Marker message thrown when no configured server answered a round trip. */
    const val NO_SERVER_MESSAGE = "error:no-server-reachable"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val LATENCY_ATTEMPTS = 5

    const val DEFAULT_DOWNLOAD_MS = 8_000L
    const val DEFAULT_UPLOAD_MS = 6_000L
    const val DOWNLOAD_STREAMS = 4
    const val UPLOAD_STREAMS = 3

    /** Streams opened immediately; the rest are only added on a proven-fast line. */
    const val INITIAL_STREAMS = 2

    /** When the ramp-up decision is taken. */
    const val RAMP_DECISION_MS = 1_500L

    /** Below this measured throughput the extra streams would only add setup noise. */
    const val RAMP_THRESHOLD_BPS = 5_000_000.0

    /**
     * Candidate order for a server preference. `"AUTO"` keeps the shipped order; an
     * explicit id puts that server first (it is still not trusted blindly — see
     * [probe]).
     */
    fun candidates(preference: String): List<SpeedTestServer> {
        val preferred = servers.firstOrNull { it.id.equals(preference, ignoreCase = true) }
            ?: return servers
        return listOf(preferred) + servers.filter { it.id != preferred.id }
    }

    fun serverById(id: String): SpeedTestServer? =
        servers.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** The servers that can take an upload, preferred one first. */
    fun uploadCapable(preference: String): List<SpeedTestServer> =
        candidates(preference).filter { it.supportsUpload }

    /**
     * One measured round trip. Throws when the server does not answer — this is the
     * reachability probe used to fail over between [servers].
     */
    suspend fun probe(server: SpeedTestServer): Long = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val connection = open(server.latencyUrl)
        try {
            val code = connection.responseCode
            if (code !in 200..399) throw IOException("${server.host} answered HTTP $code")
            (System.nanoTime() - startedAt) / 1_000_000L
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Round-trip latency + jitter. Throws [IOException] when the server is unreachable. */
    suspend fun measureLatency(
        server: SpeedTestServer = CLOUDFLARE,
        abort: Abort = Abort { false },
        onSample: (Long) -> Unit = {}
    ): LatencyResult = withContext(Dispatchers.IO) {
        // Warm-up: pays for DNS/TCP/TLS so the measured samples are pure round trips.
        runCatching { probe(server) }

        val samples = ArrayList<Long>(LATENCY_ATTEMPTS)
        repeat(LATENCY_ATTEMPTS) {
            if (abort.isAborted()) throw IOException(ABORT_MESSAGE)
            val sample = runCatching { probe(server) }.getOrNull()
            if (sample != null) {
                samples += sample
                onSample(sample)
            }
            delay(100L)
        }
        if (samples.isEmpty()) {
            throw IOException("No response from ${server.host}")
        }
        val sorted = samples.sorted()
        LatencyResult(
            minMs = sorted.first(),
            averageMs = (samples.sum().toDouble() / samples.size).toLong(),
            jitterMs = jitterOf(samples),
            samples = samples.size
        )
    }

    /** Mean absolute difference between consecutive round trips. */
    internal fun jitterOf(samples: List<Long>): Long {
        if (samples.size < 2) return 0L
        var total = 0L
        for (index in 1 until samples.size) {
            total += kotlin.math.abs(samples[index] - samples[index - 1])
        }
        return total / (samples.size - 1)
    }

    /**
     * Download throughput. [onProgress] receives (bits/second, fraction of the phase).
     * Throws [IOException] when not a single byte could be measured.
     */
    suspend fun measureDownload(
        server: SpeedTestServer = CLOUDFLARE,
        durationMs: Long = DEFAULT_DOWNLOAD_MS,
        streams: Int = DOWNLOAD_STREAMS,
        abort: Abort = Abort { false },
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val counter = AtomicLong(0L)
            val usedStreams = AtomicInteger(0)
            val startNs = System.nanoTime()
            // Timestamp of the very first byte that actually arrived. Throughput is
            // averaged over (first byte → deadline), so the TCP/TLS ramp-up cannot
            // deflate the measured speed.
            val firstByteNs = AtomicLong(0L)
            // Streams 2..n wait for this: a slow line keeps just the initial streams so
            // connection setup does not pollute the measurement.
            val ramp = CompletableDeferred<Boolean>()
            var lastBytes = 0L
            var lastProgressNs = startNs
            var rampDecided = false

            val jobs = (0 until streams).map { index ->
                async(Dispatchers.IO) {
                    if (index >= INITIAL_STREAMS && !ramp.await()) return@async
                    usedStreams.incrementAndGet()
                    var request = 0
                    while (isActive && !abort.isAborted() && elapsedMs(startNs) < durationMs) {
                        // A different size per request keeps caches/proxies honest.
                        val size = 20_000_000L + index * 1_000_000L + request * 250_000L
                        val connection = try {
                            open(server.downloadUrl(size))
                        } catch (e: Exception) {
                            return@async
                        }
                        try {
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (isActive && !abort.isAborted() && elapsedMs(startNs) < durationMs) {
                                    val read = try {
                                        input.read(buffer)
                                    } catch (e: Exception) {
                                        -1
                                    }
                                    if (read <= 0) break
                                    if (firstByteNs.get() == 0L) {
                                        firstByteNs.compareAndSet(0L, System.nanoTime())
                                    }
                                    counter.addAndGet(read.toLong())
                                }
                            }
                        } catch (e: Exception) {
                            // Stream closed at the deadline (or a hiccup): whatever arrived
                            // is real data and stays counted.
                        } finally {
                            runCatching { connection.disconnect() }
                        }
                        request++
                    }
                }
            }

            while (elapsedMs(startNs) < durationMs && !abort.isAborted()) {
                delay(PROGRESS_INTERVAL_MS)
                val nowNs = System.nanoTime()
                val bytes = counter.get()
                val fraction = (elapsedMs(startNs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                onProgress(bitsOver(bytes - lastBytes, nowNs - lastProgressNs), fraction)
                lastBytes = bytes
                lastProgressNs = nowNs

                if (!rampDecided && elapsedMs(startNs) >= RAMP_DECISION_MS) {
                    rampDecided = true
                    // Measured, not assumed: only fast lines get the extra connections.
                    val soFar = bitsOver(bytes, nowNs - startNs)
                    ramp.complete(soFar >= RAMP_THRESHOLD_BPS)
                }
            }
            // Always release the gate, otherwise the waiting coroutines would never end.
            ramp.complete(false)

            val endNs = System.nanoTime()
            val measuredMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(1L)
            // Cancel, then wait: the count below must be final, not a value that a
            // still-draining stream could grow a millisecond later.
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            val bytes = counter.get()
            if (abort.isAborted()) throw IOException(ABORT_MESSAGE)
            if (bytes <= 0L) throw IOException("Download failed — ${server.host} sent no data")
            val firstNs = firstByteNs.get()
            val activeMs = if (firstNs > 0L) {
                ((endNs - firstNs) / 1_000_000L).coerceAtLeast(1L)
            } else {
                measuredMs
            }
            ThroughputResult(
                bitsPerSecond = bytes * 8.0 / (activeMs / 1000.0),
                bytes = bytes,
                durationMs = activeMs,
                streams = usedStreams.get().coerceAtLeast(1)
            )
        }
    }

    /**
     * Upload throughput.
     *
     * Counts the bytes the socket accepted, then reads the server's response: an upload
     * the server rejected is an error, not a throughput number. [onProgress] receives
     * (bits/second, fraction of the phase). Throws [IOException] when nothing was sent.
     */
    suspend fun measureUpload(
        server: SpeedTestServer = CLOUDFLARE,
        durationMs: Long = DEFAULT_UPLOAD_MS,
        streams: Int = UPLOAD_STREAMS,
        abort: Abort = Abort { false },
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        val uploadUrl = server.uploadUrl
            ?: throw IOException("${server.name} cannot measure upload")
        coroutineScope {
            val counter = AtomicLong(0L)
            val usedStreams = AtomicInteger(0)
            val startNs = System.nanoTime()
            val firstByteNs = AtomicLong(0L)
            val ramp = CompletableDeferred<Boolean>()
            var lastBytes = 0L
            var lastProgressNs = startNs
            var rampDecided = false
            var failure: IOException? = null
            val payload = ByteArray(BUFFER_SIZE)

            val jobs = (0 until streams).map {
                async(Dispatchers.IO) {
                    if (it >= INITIAL_STREAMS && !ramp.await()) return@async
                    usedStreams.incrementAndGet()
                    val connection = try {
                        open(uploadUrl).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setChunkedStreamingMode(BUFFER_SIZE)
                            setRequestProperty("Content-Type", "application/octet-stream")
                        }
                    } catch (e: Exception) {
                        return@async
                    }
                    try {
                        connection.outputStream.use { output ->
                            while (isActive && !abort.isAborted() && elapsedMs(startNs) < durationMs) {
                                output.write(payload)
                                if (firstByteNs.get() == 0L) {
                                    firstByteNs.compareAndSet(0L, System.nanoTime())
                                }
                                counter.addAndGet(payload.size.toLong())
                            }
                            runCatching { output.flush() }
                        }
                        // Ask the server what it thinks happened before trusting the count.
                        val code = connection.responseCode
                        if (code !in 200..399) {
                            failure = IOException("${server.host} rejected the upload (HTTP $code)")
                        } else {
                            runCatching { connection.inputStream.close() }
                        }
                    } catch (e: Exception) {
                        // Socket cut when the deadline hits: the bytes already accepted are
                        // the real upload for this phase.
                    } finally {
                        runCatching { connection.disconnect() }
                    }
                }
            }

            while (elapsedMs(startNs) < durationMs && !abort.isAborted()) {
                delay(PROGRESS_INTERVAL_MS)
                val nowNs = System.nanoTime()
                val bytes = counter.get()
                val fraction = (elapsedMs(startNs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                onProgress(bitsOver(bytes - lastBytes, nowNs - lastProgressNs), fraction)
                lastBytes = bytes
                lastProgressNs = nowNs

                if (!rampDecided && elapsedMs(startNs) >= RAMP_DECISION_MS) {
                    rampDecided = true
                    ramp.complete(bitsOver(bytes, nowNs - startNs) >= RAMP_THRESHOLD_BPS)
                }
            }
            ramp.complete(false)

            val endNs = System.nanoTime()
            val measuredMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(1L)
            // Cancel, then wait: the count below must be final, not a value that a
            // still-draining stream could grow a millisecond later.
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            val bytes = counter.get()
            if (abort.isAborted()) throw IOException(ABORT_MESSAGE)
            failure?.let { throw it }
            if (bytes <= 0L) throw IOException("Upload failed — nothing could be sent")
            val firstNs = firstByteNs.get()
            val activeMs = if (firstNs > 0L) {
                ((endNs - firstNs) / 1_000_000L).coerceAtLeast(1L)
            } else {
                measuredMs
            }
            ThroughputResult(
                bitsPerSecond = bytes * 8.0 / (activeMs / 1000.0),
                bytes = bytes,
                durationMs = activeMs,
                streams = usedStreams.get().coerceAtLeast(1)
            )
        }
    }

    /**
     * Bits/second over a *measured* interval. The progress tick assumes nothing about
     * the delay (a busy device can take longer than [PROGRESS_INTERVAL_MS] to come back),
     * so the gauge is never fed a fabricated interval.
     */
    private fun bitsOver(bytesInInterval: Long, intervalNs: Long): Double {
        if (bytesInInterval <= 0L || intervalNs <= 0L) return 0.0
        return bytesInInterval * 8.0 / (intervalNs / 1_000_000_000.0)
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    private fun open(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("User-Agent", "InternetSpeedMeter/1.2 (Android)")
        return connection
    }
}
