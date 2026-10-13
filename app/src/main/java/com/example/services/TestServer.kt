package com.example.services

import java.net.URL

/**
 * A public (or user-provided) speed-test endpoint profile.
 *
 * The app talks to *one* of these at a time with plain `HttpURLConnection` — no SDK, no
 * account, no telemetry. Everything here is **pure data + URL building**, so the whole
 * selection/resolution logic is unit-testable without Android or a network.
 *
 * Two protocol flavours exist in the wild:
 *  - **Cloudflare-style**: `GET /__down?bytes=N` (a body of exactly N bytes) and
 *    `POST /__up` (the server swallows the body and answers `200`).
 *  - **LibreSpeed-style backends**: `GET /backend/garbage.php?ckSize=<megabytes>` and
 *    `POST /backend/empty.php`.
 *
 * [sizeUnitBytes] is what makes one template serve both: Cloudflare counts **bytes**,
 * LibreSpeed counts **megabytes**.
 */
data class TestServer(
    /** Stable id used in preferences. See [SpeedTestServers]. */
    val id: String,
    /** Human-readable label shown in Settings. */
    val label: String,
    /** Host (without scheme) the test actually talks to — shown on the result card. */
    val host: String,
    /** Download template; `{size}` is replaced by [downloadUrl]. */
    val downloadTemplate: String,
    /** Upload URL, or `null` when the endpoint cannot accept an upload. */
    val uploadUrl: String?,
    /** Cheap request used to measure latency/jitter. */
    val latencyUrl: String,
    /** 1 → the template size is in bytes; 1 MiB → the template size is in megabytes. */
    val sizeUnitBytes: Long = 1L
) {
    /** The URL a download request of [bytes] bytes should use. */
    fun downloadUrl(bytes: Long): String {
        val size = if (sizeUnitBytes <= 1L) {
            bytes.coerceAtLeast(0L)
        } else {
            (bytes / sizeUnitBytes).coerceAtLeast(1L)
        }
        return downloadTemplate.replace(SIZE_TOKEN, size.toString())
    }

    /** True when [uploadUrl] is present — media with read-only endpoints are common. */
    val supportsUpload: Boolean get() = !uploadUrl.isNullOrBlank()

    private companion object {
        const val SIZE_TOKEN = "{size}"
    }
}

/**
 * The built-in endpoint profiles.
 *
 * No measurement logic lives here — this object only answers "which URLs may I talk to,
 * and in which order should I try them".
 */
object SpeedTestServers {

    const val ID_AUTO = "AUTO"
    const val ID_CLOUDFLARE = "CLOUDFLARE"
    const val ID_LIBRESPEED = "LIBRESPEED"
    const val ID_CUSTOM = "CUSTOM"

    /** Flavour of a user-supplied host (see [custom]). */
    const val FLAVOUR_CLOUDFLARE = "CLOUDFLARE"
    const val FLAVOUR_LIBRESPEED = "LIBRESPEED"

    /**
     * Cloudflare's public speed endpoint — the default: anycast, no account, and it
     * accepts both directions.
     */
    val CLOUDFLARE = TestServer(
        id = ID_CLOUDFLARE,
        label = "Cloudflare (default)",
        host = "speed.cloudflare.com",
        downloadTemplate = "https://speed.cloudflare.com/__down?bytes={size}",
        uploadUrl = "https://speed.cloudflare.com/__up",
        latencyUrl = "https://speed.cloudflare.com/__down?bytes=0"
    )

    /**
     * LibreSpeed's public backend — the fallback for networks where the Cloudflare host
     * is blocked (corporate DNS/proxies, some ISPs). Same math, same privacy rules.
     */
    val LIBRESPEED = TestServer(
        id = ID_LIBRESPEED,
        label = "LibreSpeed (fallback)",
        host = "librespeed.org",
        downloadTemplate = "https://librespeed.org/backend/garbage.php?ckSize={size}",
        uploadUrl = "https://librespeed.org/backend/empty.php",
        latencyUrl = "https://librespeed.org/backend/empty.php",
        sizeUnitBytes = 1024L * 1024L
    )

    /** All built-in profiles, in preference order for [list]. */
    val BUILT_IN: List<TestServer> = listOf(CLOUDFLARE, LIBRESPEED)

    /** The profile with [id], or `null` for an unknown/CUSTOM id. */
    fun byId(id: String?): TestServer? = BUILT_IN.firstOrNull { it.id == id }

    /**
     * A user-supplied host. [flavour] decides the URL layout, so both protocol families
     * work. Returns `null` when [baseUrl] is not a usable `http(s)` URL — the settings UI
     * then keeps the previous server instead of breaking the test.
     */
    fun custom(baseUrl: String?, flavour: String?): TestServer? {
        val base = baseUrl?.trim()?.trimEnd('/') ?: return null
        if (base.isEmpty()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        val host = try {
            URL(base).host ?: return null
        } catch (e: Exception) {
            return null
        }
        return if (flavour == FLAVOUR_LIBRESPEED) {
            TestServer(
                id = ID_CUSTOM,
                label = "Custom backend",
                host = host,
                downloadTemplate = "$base/backend/garbage.php?ckSize={size}",
                uploadUrl = "$base/backend/empty.php",
                latencyUrl = "$base/backend/empty.php",
                sizeUnitBytes = 1024L * 1024L
            )
        } else {
            TestServer(
                id = ID_CUSTOM,
                label = "Custom host",
                host = host,
                downloadTemplate = "$base/__down?bytes={size}",
                uploadUrl = "$base/__up",
                latencyUrl = "$base/__down?bytes=0"
            )
        }
    }

    /**
     * Ordered candidates for one test run.
     *
     *  - `AUTO` (default) → Cloudflare first, LibreSpeed as a **fallback**: if the first
     *    host cannot even answer the latency probe (blocked DNS, captive portal, corporate
     *    proxy), the test retries on the second one instead of failing outright.
     *  - An explicit choice → only that host.
     *  - `CUSTOM` → the user's URL; if it is unusable, the default Cloudflare host is used
     *    so a typo cannot make the feature dead.
     */
    fun candidates(preference: String?, customUrl: String?, customFlavour: String?): List<TestServer> {
        return when (preference) {
            ID_CLOUDFLARE -> listOf(CLOUDFLARE)
            ID_LIBRESPEED -> listOf(LIBRESPEED)
            ID_CUSTOM -> listOfNotNull(custom(customUrl, customFlavour)).ifEmpty { listOf(CLOUDFLARE) }
            else -> listOf(CLOUDFLARE, LIBRESPEED)
        }
    }
}

/**
 * How much mobile data one test is expected to spend, so the app can ask before it
 * burns the user's pack. Uses the speed the device last actually measured; falls back to
 * conservative assumptions when nothing has been measured yet.
 *
 * Pure maths on purpose (unit-tested): the UI must never show a made-up "~30 MB" that has
 * no relation to the configured phase durations.
 */
object TestDataCost {

    /** Used only when the device has no measured speed yet (~24 Mbps down). */
    const val ASSUMED_DOWNLOAD_BPS = 3_000_000L

    /** Used only when the device has no measured speed yet (~4.8 Mbps up). */
    const val ASSUMED_UPLOAD_BPS = 600_000L

    /** Never warn about less than this — the phases have a real minimum cost. */
    const val FLOOR_BYTES = 5L * 1024L * 1024L

    /** Protocol/TLS overhead allowance (~10 %). */
    private const val OVERHEAD = 1.1

    fun estimateBytes(
        downloadBps: Long?,
        uploadBps: Long?,
        downloadMs: Long = SpeedTestEngine.DEFAULT_DOWNLOAD_MS,
        uploadMs: Long = SpeedTestEngine.DEFAULT_UPLOAD_MS,
        uploadEnabled: Boolean = true
    ): Long {
        val down = (downloadBps ?: 0L).let { if (it > 0L) it else ASSUMED_DOWNLOAD_BPS }
        val up = (uploadBps ?: 0L).let { if (it > 0L) it else ASSUMED_UPLOAD_BPS }
        val downloadBytes = down.toDouble() * (downloadMs / 1000.0)
        val uploadBytes = if (uploadEnabled) up.toDouble() * (uploadMs / 1000.0) else 0.0
        val total = ((downloadBytes + uploadBytes) * OVERHEAD).toLong()
        return total.coerceAtLeast(FLOOR_BYTES)
    }

    /** Rounded-up megabytes for the confirmation dialog (never `0 MB`). */
    fun estimateMegabytes(
        downloadBps: Long?,
        uploadBps: Long?,
        downloadMs: Long = SpeedTestEngine.DEFAULT_DOWNLOAD_MS,
        uploadMs: Long = SpeedTestEngine.DEFAULT_UPLOAD_MS,
        uploadEnabled: Boolean = true
    ): Int {
        val bytes = estimateBytes(downloadBps, uploadBps, downloadMs, uploadMs, uploadEnabled)
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return mb.toInt().coerceAtLeast(1)
    }
}
