package com.example.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Round-trip statistics of the latency probe (milliseconds). */
data class LatencyResult(
    val minMs: Long,
    val averageMs: Long,
    val jitterMs: Long,
    val samples: Int
)

/**
 * Result of one throughput phase.
 *
 * [bitsPerSecond] is application-level throughput over the **measured window**
 * ([bytes] bytes in [durationMs] ms). [totalBytes] is everything the phase moved,
 * including the bytes that arrived before the window opened (TCP slow start) — that is
 * what the user's data plan paid for, so it is kept separately.
 */
data class ThroughputResult(
    val bitsPerSecond: Double,
    val bytes: Long,
    val durationMs: Long,
    val streams: Int,
    val totalBytes: Long = bytes,
    /** HTTP status the server answered the upload verification with (0 = not applicable). */
    val serverAckCode: Int = 0
) {
    val megabytesPerSecond: Double get() = bytes / 1024.0 / 1024.0 / (durationMs / 1000.0)
}

/** Thrown when a running test is stopped on purpose (not a failure of the connection). */
class TestAbortedException(message: String) : Exception(message)

/** Why a phase could not produce a number. The UI maps this to localized copy. */
enum class SpeedTestFailure {
    /** The latency probe got no answer at all — the host cannot be reached from here. */
    UNREACHABLE,
    /** The download phase ended without a single byte. */
    NO_DOWNLOAD_DATA,
    /** The endpoint profile has no upload URL. */
    NO_UPLOAD_ENDPOINT,
    /** The server never confirmed (HTTP 2xx/3xx) that it received the verification upload. */
    UPLOAD_NOT_ACKNOWLEDGED,
    /** The upload streams could not send anything. */
    NOTHING_SENT
}

/**
 * A measurement failure with a machine-readable [failure] kind, the [host] involved and
 * — when the server answered — the HTTP [httpCode]. The engine has no `Context`, so the
 * message is an English fallback; the UI layer renders the localized text.
 */
class SpeedTestException(
    val failure: SpeedTestFailure,
    val host: String,
    val httpCode: Int = 0,
    message: String
) : IOException(message)

/**
 * Cooperative abort flag shared by the phases of one test run.
 *
 * Coroutine cancellation alone cannot interrupt a **blocking socket read**, which would
 * let a Wi-Fi → mobile switch keep downloading on the user's metered data for seconds.
 * The read loops poll this flag, and every phase runs a sentinel that **disconnects the
 * live sockets** the moment the flag flips, so a blocked `read()` fails immediately
 * instead of waiting for its 10 s timeout.
 */
class TestAbortSignal {

    @Volatile
    var isAborted: Boolean = false
        private set

    @Volatile
    var reason: String? = null
        private set

    fun abort(reason: String) {
        this.reason = reason
        this.isAborted = true
    }

    fun throwIfAborted() {
        if (isAborted) throw TestAbortedException(reason ?: "Test stopped")
    }
}

/**
 * The three measurements the view model needs, behind an interface so the view model's
 * rules (metered confirmation, endpoint fallback, abort on a transport change) can be unit
 * tested with a fake runner. [Live] is the real thing.
 */
interface SpeedTestRunner {
    suspend fun latency(server: TestServer, abort: TestAbortSignal, onSample: (Long) -> Unit): LatencyResult
    suspend fun download(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult
    suspend fun upload(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult

    object Live : SpeedTestRunner {
        override suspend fun latency(server: TestServer, abort: TestAbortSignal, onSample: (Long) -> Unit): LatencyResult =
            SpeedTestEngine.measureLatency(server, abort, onSample)

        override suspend fun download(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult =
            SpeedTestEngine.measureDownload(server, abort = abort, onProgress = onProgress)

        override suspend fun upload(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult =
            SpeedTestEngine.measureUpload(server, abort = abort, onProgress = onProgress)
    }
}

/**
 * The **active** measurement: this is the part that answers "what is my actual internet
 * speed right now?" the way dedicated speed-test apps do, instead of only observing the
 * traffic the device happens to be generating.
 *
 * How it measures (all of it real, none of it simulated):
 *
 *  - **Latency / jitter** — one throw-away warm-up request (pays for DNS + TCP + TLS),
 *    then measured round trips on the *kept-alive* connection (the response body is
 *    drained and the socket is left to the pool, so a sample is a request/response
 *    round trip, not a fresh TLS handshake); jitter = mean absolute difference of
 *    consecutive samples.
 *  - **Download** — several parallel `GET` streams, counting the bytes that actually
 *    arrive, for a fixed time budget. A stream that finishes its request quickly asks for
 *    a bigger one next time ([nextRequestBytes]) and reuses the kept-alive socket, so a
 *    gigabit line is measured with the pipe full instead of being spent on request
 *    setup and TCP slow start over and over.
 *  - **Upload** — one small fixed-length `POST` the server must **acknowledge** (HTTP
 *    2xx/3xx), then several parallel chunked `POST` streams, counting the bytes the socket
 *    accepted. A proxy or captive portal that swallows uploads therefore fails the test
 *    honestly instead of producing a number.
 *
 * Throughput is averaged over a **measured window**: it opens [DOWNLOAD_GRACE_MS] /
 * [UPLOAD_GRACE_MS] after the first byte (TCP slow start is warm-up, not line speed —
 * the same rule the reference speed-test implementations use) and closes at the deadline.
 * On a very slow line, where that window would be shorter than [MIN_WINDOW_MS], the
 * average runs from the first byte instead. Nothing is extrapolated.
 *
 * The endpoint is a [TestServer] profile (see [SpeedTestServers]) so the test survives a
 * blocked host, and every phase takes a [TestAbortSignal] so a network change mid-test
 * can stop it instantly.
 *
 * Throughput is reported as `bytes × 8 / seconds` (decimal Mbps) — the unit ISPs advertise.
 * Multi-stream on purpose: a single TCP stream often cannot saturate a fast connection,
 * which is why single-stream tests under-report real line speed.
 *
 * No third-party SDK, no analytics: it is plain `HttpURLConnection` against a public
 * test endpoint, and it runs **only** when the user taps "Start test".
 */
object SpeedTestEngine {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val ABORT_POLL_MS = 100L
    private const val LATENCY_ATTEMPTS = 5
    private const val LATENCY_DRAIN_LIMIT = 256 * 1024L

    /** How long the deadline waits for a healthy stream to finish its current chunk. */
    private const val JOIN_GRACE_MS = 250L

    const val DEFAULT_DOWNLOAD_MS = 8_000L
    const val DEFAULT_UPLOAD_MS = 6_000L
    const val DOWNLOAD_STREAMS = 4
    const val UPLOAD_STREAMS = 3

    /** Warm-up excluded from the averaged window (see the class docs). */
    const val DOWNLOAD_GRACE_MS = 1_500L
    const val UPLOAD_GRACE_MS = 1_000L

    /** Below this the grace window is not applied — the whole transfer is the measurement. */
    const val MIN_WINDOW_MS = 1_000L

    /** Size of the fixed-length upload the server has to acknowledge before the streams start. */
    const val UPLOAD_ACK_BYTES = 32 * 1024

    /** First download request per stream (bytes); see [nextRequestBytes]. */
    const val INITIAL_REQUEST_BYTES = 20_000_000L

    /** Largest single download request (both endpoint flavours serve this comfortably). */
    const val MAX_REQUEST_BYTES = 100_000_000L

    /** A request that completes faster than this is too small to keep a fast line busy. */
    const val FAST_REQUEST_MS = 1_000L

    /** Round-trip latency + jitter. Throws [SpeedTestException] when the server is unreachable. */
    suspend fun measureLatency(
        server: TestServer = SpeedTestServers.CLOUDFLARE,
        abort: TestAbortSignal = TestAbortSignal(),
        onSample: (Long) -> Unit = {}
    ): LatencyResult = withContext(Dispatchers.IO) {
        val connections = ConnectionSet()
        coroutineScope {
            val sentinel = launchAbortSentinel(abort, connections)
            try {
                // Warm-up: pays for DNS/TCP/TLS so the measured samples are pure round trips.
                runCatching { probeLatency(server.latencyUrl, connections) }

                val samples = ArrayList<Long>(LATENCY_ATTEMPTS)
                repeat(LATENCY_ATTEMPTS) {
                    abort.throwIfAborted()
                    val sample = runCatching { probeLatency(server.latencyUrl, connections) }.getOrNull()
                    if (sample != null) {
                        samples += sample
                        onSample(sample)
                    }
                    delay(100L)
                }
                abort.throwIfAborted()
                if (samples.isEmpty()) {
                    throw SpeedTestException(
                        SpeedTestFailure.UNREACHABLE, server.host,
                        message = "No response from ${server.host}"
                    )
                }
                val sorted = samples.sorted()
                LatencyResult(
                    minMs = sorted.first(),
                    averageMs = (samples.sum().toDouble() / samples.size).toLong(),
                    jitterMs = jitterOf(samples),
                    samples = samples.size
                )
            } finally {
                sentinel.cancel()
            }
        }
    }

    /**
     * One timed round trip. The (tiny) body is drained and the connection is **not**
     * disconnected, so the pool keeps the socket alive and the next sample does not pay
     * for a new TLS handshake — a "ping" has to be a round trip, not a connection setup.
     */
    private fun probeLatency(url: String, connections: ConnectionSet): Long {
        val startedAt = System.nanoTime()
        val connection = open(url)
        connections.track(connection)
        try {
            val code = connection.responseCode
            if (code !in 200..399) throw IOException("Latency probe failed (HTTP $code)")
            connection.inputStream.use { input -> drain(input) }
            return (System.nanoTime() - startedAt) / 1_000_000L
        } catch (e: Exception) {
            // A failed probe must not poison the pool: drop this socket, keep the others.
            runCatching { connection.disconnect() }
            throw e
        } finally {
            connections.untrack(connection)
        }
    }

    private fun drain(input: java.io.InputStream) {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total < LATENCY_DRAIN_LIMIT) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
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
     * Throws [SpeedTestException] when not a single byte could be measured,
     * [TestAbortedException] when the test was stopped.
     */
    suspend fun measureDownload(
        server: TestServer = SpeedTestServers.CLOUDFLARE,
        durationMs: Long = DEFAULT_DOWNLOAD_MS,
        streams: Int = DOWNLOAD_STREAMS,
        abort: TestAbortSignal = TestAbortSignal(),
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        abort.throwIfAborted()
        val connections = ConnectionSet()
        coroutineScope {
            val sentinel = launchAbortSentinel(abort, connections)
            val counter = AtomicLong(0L)
            val firstByteNs = AtomicLong(0L)
            val startNs = System.nanoTime()

            val jobs = (0 until streams).map { index ->
                launch(Dispatchers.IO) {
                    var request = 0
                    var requestBytes = INITIAL_REQUEST_BYTES + index * 1_000_000L
                    while (isActive && !abort.isAborted && elapsedMs(startNs) < durationMs) {
                        // A slightly different size per request keeps caches/proxies honest.
                        val size = requestBytes + request * 250_000L
                        val connection = try {
                            open(server.downloadUrl(size))
                        } catch (e: Exception) {
                            return@launch
                        }
                        connections.track(connection)
                        val requestStartNs = System.nanoTime()
                        var bodyComplete = false
                        try {
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (isActive && !abort.isAborted && elapsedMs(startNs) < durationMs) {
                                    val read = try {
                                        input.read(buffer)
                                    } catch (e: Exception) {
                                        -1
                                    }
                                    if (read < 0) {
                                        bodyComplete = true
                                        break
                                    }
                                    if (read == 0) continue
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
                            connections.untrack(connection)
                            // A fully read body leaves a warm keep-alive socket in the pool —
                            // the next request skips TCP/TLS setup and slow start. A body cut
                            // off by the deadline must be disconnected instead.
                            if (!bodyComplete) runCatching { connection.disconnect() }
                        }
                        requestBytes = nextRequestBytes(
                            previousBytes = requestBytes,
                            completedInMs = (System.nanoTime() - requestStartNs) / 1_000_000L,
                            bodyComplete = bodyComplete
                        )
                        request++
                    }
                }
            }

            val window = runPhaseClock(startNs, durationMs, DOWNLOAD_GRACE_MS, counter, firstByteNs, abort, onProgress)
            val closed = closeStreams(jobs, connections, counter)
            sentinel.cancel()
            abort.throwIfAborted()
            if (closed.bytes <= 0L) {
                throw SpeedTestException(
                    SpeedTestFailure.NO_DOWNLOAD_DATA, server.host,
                    message = "Download failed — ${server.host} sent no data"
                )
            }
            computeThroughput(
                totalBytes = closed.bytes,
                startNs = startNs,
                firstByteNs = firstByteNs.get(),
                graceNs = window.graceNs,
                graceBytes = window.graceBytes,
                endNs = closed.endNs,
                streams = streams
            )
        }
    }

    /**
     * Upload throughput. First a fixed-length [UPLOAD_ACK_BYTES] `POST` that the server
     * has to answer with 2xx/3xx (proof that the endpoint really accepts uploads), then the
     * timed chunked streams. [onProgress] receives (bits/second, fraction of the phase).
     * Throws [SpeedTestException] when the endpoint has no upload URL, never acknowledged
     * the verification upload or nothing could be sent; [TestAbortedException] when the
     * test was stopped.
     */
    suspend fun measureUpload(
        server: TestServer = SpeedTestServers.CLOUDFLARE,
        durationMs: Long = DEFAULT_UPLOAD_MS,
        streams: Int = UPLOAD_STREAMS,
        abort: TestAbortSignal = TestAbortSignal(),
        onProgress: (Double, Float) -> Unit = { _, _ -> }
    ): ThroughputResult = withContext(Dispatchers.IO) {
        abort.throwIfAborted()
        val uploadUrl = server.uploadUrl
        if (uploadUrl.isNullOrBlank()) {
            throw SpeedTestException(
                SpeedTestFailure.NO_UPLOAD_ENDPOINT, server.host,
                message = "${server.host} cannot accept uploads"
            )
        }
        val connections = ConnectionSet()
        coroutineScope {
            val sentinel = launchAbortSentinel(abort, connections)
            val ackCode = acknowledgeUpload(uploadUrl, server.host, connections, abort)
            abort.throwIfAborted()

            val counter = AtomicLong(0L)
            val firstByteNs = AtomicLong(0L)
            val startNs = System.nanoTime()
            val payload = ByteArray(BUFFER_SIZE)

            val jobs = (0 until streams).map {
                launch(Dispatchers.IO) {
                    val connection = try {
                        open(uploadUrl).apply {
                            requestMethod = "POST"
                            doOutput = true
                            setChunkedStreamingMode(BUFFER_SIZE)
                            setRequestProperty("Content-Type", "application/octet-stream")
                        }
                    } catch (e: Exception) {
                        return@launch
                    }
                    connections.track(connection)
                    try {
                        connection.outputStream.use { output ->
                            while (isActive && !abort.isAborted && elapsedMs(startNs) < durationMs) {
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
                        connections.untrack(connection)
                        runCatching { connection.disconnect() }
                    }
                }
            }

            val window = runPhaseClock(startNs, durationMs, UPLOAD_GRACE_MS, counter, firstByteNs, abort, onProgress)
            val closed = closeStreams(jobs, connections, counter)
            sentinel.cancel()
            abort.throwIfAborted()
            if (closed.bytes <= 0L) {
                throw SpeedTestException(
                    SpeedTestFailure.NOTHING_SENT, server.host,
                    message = "Upload failed — nothing could be sent to ${server.host}"
                )
            }
            computeThroughput(
                totalBytes = closed.bytes,
                startNs = startNs,
                firstByteNs = firstByteNs.get(),
                graceNs = window.graceNs,
                graceBytes = window.graceBytes,
                endNs = closed.endNs,
                streams = streams
            ).copy(serverAckCode = ackCode)
        }
    }

    /**
     * The upload verification: a fixed-length body the server must read completely before
     * it can answer. A 2xx/3xx is the acknowledgement; anything else (or no answer) means
     * "this endpoint does not really take uploads" and the phase fails instead of
     * measuring how fast a proxy discards bytes.
     */
    private fun acknowledgeUpload(
        uploadUrl: String,
        host: String,
        connections: ConnectionSet,
        abort: TestAbortSignal
    ): Int {
        val connection = try {
            open(uploadUrl).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(UPLOAD_ACK_BYTES)
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        } catch (e: Exception) {
            abort.throwIfAborted()
            throw SpeedTestException(
                SpeedTestFailure.UPLOAD_NOT_ACKNOWLEDGED, host,
                message = "$host did not acknowledge the upload"
            )
        }
        connections.track(connection)
        try {
            connection.outputStream.use { output ->
                output.write(ByteArray(UPLOAD_ACK_BYTES))
                output.flush()
            }
            val code = connection.responseCode
            if (code !in 200..399) {
                throw SpeedTestException(
                    SpeedTestFailure.UPLOAD_NOT_ACKNOWLEDGED, host, code,
                    "$host did not accept the upload (HTTP $code)"
                )
            }
            return code
        } catch (e: SpeedTestException) {
            throw e
        } catch (e: Exception) {
            // The sentinel cuts this socket too when the run is aborted: report the abort,
            // not a server that "did not acknowledge".
            abort.throwIfAborted()
            throw SpeedTestException(
                SpeedTestFailure.UPLOAD_NOT_ACKNOWLEDGED, host,
                message = "$host did not acknowledge the upload"
            )
        } finally {
            connections.untrack(connection)
            runCatching { connection.disconnect() }
        }
    }

    private class PhaseWindow(val graceNs: Long, val graceBytes: Long)

    private class ClosedStreams(val endNs: Long, val bytes: Long)

    /**
     * The progress loop of a throughput phase: feeds the gauge every ~250 ms with the rate
     * over the *measured* interval and records the moment the grace period ends (counter
     * value + timestamp) so the final average can exclude the warm-up.
     */
    private suspend fun runPhaseClock(
        startNs: Long,
        durationMs: Long,
        graceMs: Long,
        counter: AtomicLong,
        firstByteNs: AtomicLong,
        abort: TestAbortSignal,
        onProgress: (Double, Float) -> Unit
    ): PhaseWindow {
        var lastBytes = 0L
        var lastProgressNs = startNs
        var graceNs = 0L
        var graceBytes = 0L
        while (elapsedMs(startNs) < durationMs && !abort.isAborted) {
            delay(PROGRESS_INTERVAL_MS)
            val nowNs = System.nanoTime()
            val bytes = counter.get()
            // The warm-up is counted from the first byte (slow start begins there), not
            // from the moment the request was sent.
            val firstByte = firstByteNs.get()
            if (graceNs == 0L && firstByte > 0L && (nowNs - firstByte) / 1_000_000L >= graceMs) {
                graceNs = nowNs
                graceBytes = bytes
            }
            val fraction = (elapsedMs(startNs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            onProgress(bitsOver(bytes - lastBytes, nowNs - lastProgressNs), fraction)
            lastBytes = bytes
            lastProgressNs = nowNs
        }
        return PhaseWindow(graceNs, graceBytes)
    }

    /**
     * Deadline handling. Healthy streams leave their loop on their own within one chunk,
     * so they get a short grace to finish the read/write already in flight (those bytes
     * are real and count). Then the end time and the counter are taken **together**, and
     * only afterwards are the stragglers (a socket stalled by a network change, for
     * instance) cut by disconnecting them — never by waiting for a 10 s read timeout.
     */
    private suspend fun closeStreams(jobs: List<Job>, connections: ConnectionSet, counter: AtomicLong): ClosedStreams {
        withTimeoutOrNull(JOIN_GRACE_MS) { jobs.joinAll() }
        val endNs = System.nanoTime()
        val bytes = counter.get()
        connections.disconnectAll()
        jobs.forEach { it.cancel() }
        withTimeoutOrNull(JOIN_GRACE_MS) { jobs.joinAll() }
        return ClosedStreams(endNs, bytes)
    }

    /**
     * Pure window maths (unit-tested in `SpeedTestMathTest`).
     *
     * - no first byte → the whole phase is the window (the caller has already thrown when
     *   nothing arrived);
     * - the window normally runs from the end of the grace period to [endNs];
     * - when that window would be shorter than [MIN_WINDOW_MS] or carry no bytes (a slow
     *   line still in slow start, or a late first byte), it runs from the first byte.
     */
    internal fun computeThroughput(
        totalBytes: Long,
        startNs: Long,
        firstByteNs: Long,
        graceNs: Long,
        graceBytes: Long,
        endNs: Long,
        streams: Int
    ): ThroughputResult {
        var windowStartNs = if (firstByteNs > 0L) firstByteNs else startNs
        var windowBytes = totalBytes
        val graceWindowMs = (endNs - graceNs) / 1_000_000L
        if (graceNs > windowStartNs && graceWindowMs >= MIN_WINDOW_MS && totalBytes - graceBytes > 0L) {
            windowStartNs = graceNs
            windowBytes = totalBytes - graceBytes
        }
        val durationMs = ((endNs - windowStartNs) / 1_000_000L).coerceAtLeast(1L)
        return ThroughputResult(
            bitsPerSecond = windowBytes * 8.0 / (durationMs / 1000.0),
            bytes = windowBytes,
            durationMs = durationMs,
            streams = streams,
            totalBytes = totalBytes
        )
    }

    /**
     * Pure request-sizing rule (unit-tested in `SpeedTestMathTest`): a body that was read
     * to the end in under [FAST_REQUEST_MS] doubles the next request (up to
     * [MAX_REQUEST_BYTES]) so a fast line spends its time receiving, not re-requesting.
     * A body that took longer, or was cut off by the deadline, keeps its size — on a slow
     * line the first byte matters more than the request length.
     */
    internal fun nextRequestBytes(previousBytes: Long, completedInMs: Long, bodyComplete: Boolean): Long {
        if (!bodyComplete || completedInMs >= FAST_REQUEST_MS) return previousBytes
        return (previousBytes * 2L).coerceAtMost(MAX_REQUEST_BYTES)
    }

    /**
     * Watches the abort flag from an IO thread and tears the phase's sockets down the
     * moment it flips — also when the phase itself is cancelled while the flag is set
     * (the view model raises the flag *before* cancelling the job for exactly that reason).
     */
    private fun CoroutineScope.launchAbortSentinel(abort: TestAbortSignal, connections: ConnectionSet): Job =
        launch(Dispatchers.IO) {
            try {
                while (isActive && !abort.isAborted) {
                    delay(ABORT_POLL_MS)
                }
            } finally {
                if (abort.isAborted) connections.disconnectAll()
            }
        }

    /** The sockets a phase currently has open; disconnecting them unblocks any pending read/write. */
    private class ConnectionSet {
        private val live = CopyOnWriteArraySet<HttpURLConnection>()
        fun track(connection: HttpURLConnection) { live += connection }
        fun untrack(connection: HttpURLConnection) { live -= connection }
        fun disconnectAll() {
            live.forEach { runCatching { it.disconnect() } }
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
        connection.setRequestProperty("User-Agent", "InternetSpeedMeter (Android)")
        return connection
    }
}
