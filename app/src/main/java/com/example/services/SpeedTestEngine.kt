package com.example.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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
    val streams: Int
) {
    val megabytesPerSecond: Double get() = bytes / 1024.0 / 1024.0 / (durationMs / 1000.0)
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
 *  - **Download** — several parallel `GET` streams from Cloudflare's public speed
 *    endpoint, counting the bytes that actually arrive, for a fixed time budget.
 *  - **Upload** — several parallel chunked `POST` streams, counting the bytes the socket
 *    actually accepted.
 *
 * Throughput is reported as `bits × 8 / seconds` (decimal Mbps) — the unit ISPs advertise.
 * Multi-stream on purpose: a single TCP stream often cannot saturate a fast connection,
 * which is why single-stream tests under-report real line speed.
 *
 * No third-party SDK, no analytics: it is plain `HttpURLConnection` against a public
 * test endpoint, and it runs **only** when the user taps "Start test".
 */
object SpeedTestEngine {

    const val SERVER_NAME = "speed.cloudflare.com"

    private const val DOWNLOAD_ENDPOINT = "https://speed.cloudflare.com/__down"
    private const val UPLOAD_ENDPOINT = "https://speed.cloudflare.com/__up"
    private const val LATENCY_ENDPOINT = "https://speed.cloudflare.com/__down?bytes=0"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val LATENCY_ATTEMPTS = 5

    const val DEFAULT_DOWNLOAD_MS = 8_000L
    const val DEFAULT_UPLOAD_MS = 6_000L
    const val DOWNLOAD_STREAMS = 4
    const val UPLOAD_STREAMS = 3

    /** Round-trip latency + jitter. Throws [IOException] when the server is unreachable. */
    suspend fun measureLatency(onSample: (Long) -> Unit = {}): LatencyResult =
        withContext(Dispatchers.IO) {
            // Warm-up: pays for DNS/TCP/TLS so the measured samples are pure round trips.
            runCatching { probeLatency() }

            val samples = ArrayList<Long>(LATENCY_ATTEMPTS)
            repeat(LATENCY_ATTEMPTS) {
                val sample = runCatching { probeLatency() }.getOrNull()
                if (sample != null) {
                    samples += sample
                    onSample(sample)
                }
                delay(100L)
            }
            if (samples.isEmpty()) {
                throw IOException("No response from $SERVER_NAME")
            }
            val sorted = samples.sorted()
            LatencyResult(
                minMs = sorted.first(),
                averageMs = (samples.sum().toDouble() / samples.size).toLong(),
                jitterMs = jitterOf(samples),
                samples = samples.size
            )
        }

    private fun probeLatency(): Long {
        val startedAt = System.nanoTime()
        val connection = open(LATENCY_ENDPOINT)
        return try {
            val code = connection.responseCode
            if (code !in 200..399) throw IOException("Latency probe failed (HTTP $code)")
            (System.nanoTime() - startedAt) / 1_000_000L
        } finally {
            runCatching { connection.disconnect() }
        }
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
        durationMs: Long = DEFAULT_DOWNLOAD_MS,
        streams: Int = DOWNLOAD_STREAMS,
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val counter = AtomicLong(0L)
            val startNs = System.nanoTime()
            // Timestamp of the very first byte that actually arrived. Throughput is
            // averaged over (first byte → deadline), so the TCP/TLS ramp-up cannot
            // deflate the measured speed.
            val firstByteNs = AtomicLong(0L)
            var lastBytes = 0L
            var lastProgressNs = startNs

            val jobs = (0 until streams).map { index ->
                async(Dispatchers.IO) {
                    var request = 0
                    while (isActive && elapsedMs(startNs) < durationMs) {
                        // A different size per request keeps caches/proxies honest.
                        val size = 20_000_000L + index * 1_000_000L + request * 250_000L
                        val connection = try {
                            open("$DOWNLOAD_ENDPOINT?bytes=$size")
                        } catch (e: Exception) {
                            return@async
                        }
                        try {
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (isActive && elapsedMs(startNs) < durationMs) {
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

            while (elapsedMs(startNs) < durationMs) {
                delay(PROGRESS_INTERVAL_MS)
                val nowNs = System.nanoTime()
                val bytes = counter.get()
                val fraction = (elapsedMs(startNs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                onProgress(bitsOver(bytes - lastBytes, nowNs - lastProgressNs), fraction)
                lastBytes = bytes
                lastProgressNs = nowNs
            }

            val endNs = System.nanoTime()
            val measuredMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(1L)
            jobs.forEach { it.cancel() }
            val bytes = counter.get()
            if (bytes <= 0L) throw IOException("Download failed — the test server sent no data")
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
                streams = streams
            )
        }
    }

    /**
     * Upload throughput. Counts the bytes the socket accepted; [onProgress] receives
     * (bits/second, fraction of the phase). Throws [IOException] when nothing was sent.
     */
    suspend fun measureUpload(
        durationMs: Long = DEFAULT_UPLOAD_MS,
        streams: Int = UPLOAD_STREAMS,
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val counter = AtomicLong(0L)
            val startNs = System.nanoTime()
            val firstByteNs = AtomicLong(0L)
            var lastBytes = 0L
            var lastProgressNs = startNs
            val payload = ByteArray(BUFFER_SIZE)

            val jobs = (0 until streams).map {
                async(Dispatchers.IO) {
                    val connection = try {
                        open(UPLOAD_ENDPOINT).apply {
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
                            while (isActive && elapsedMs(startNs) < durationMs) {
                                output.write(payload)
                                if (firstByteNs.get() == 0L) {
                                    firstByteNs.compareAndSet(0L, System.nanoTime())
                                }
                                counter.addAndGet(payload.size.toLong())
                            }
                            runCatching { output.flush() }
                        }
                    } catch (e: Exception) {
                        // Socket cut when the deadline hits: the bytes already accepted are
                        // the real upload for this phase.
                    } finally {
                        runCatching { connection.disconnect() }
                    }
                }
            }

            while (elapsedMs(startNs) < durationMs) {
                delay(PROGRESS_INTERVAL_MS)
                val nowNs = System.nanoTime()
                val bytes = counter.get()
                val fraction = (elapsedMs(startNs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                onProgress(bitsOver(bytes - lastBytes, nowNs - lastProgressNs), fraction)
                lastBytes = bytes
                lastProgressNs = nowNs
            }

            val endNs = System.nanoTime()
            val measuredMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(1L)
            jobs.forEach { it.cancel() }
            val bytes = counter.get()
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
                streams = streams
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
        connection.setRequestProperty("User-Agent", "InternetSpeedMeter/1.1 (Android)")
        return connection
    }
}
