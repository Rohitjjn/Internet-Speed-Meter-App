package com.example

import com.example.services.SpeedTestServers
import com.example.services.TestDataCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the endpoint layer of the active speed test: URL building, candidate
 * ordering (the fallback rule) and the mobile-data cost estimate shown before a test.
 *
 * These exist because a speed test that silently cannot run (blocked host) or silently
 * spends 100 MB is worse than no speed test at all.
 */
class TestServerTest {

    // ------------------------------------------------------------------ URL building

    @Test
    fun cloudflareTemplateUsesByteSizes() {
        val url = SpeedTestServers.CLOUDFLARE.downloadUrl(20_000_000L)
        assertEquals("https://speed.cloudflare.com/__down?bytes=20000000", url)
    }

    @Test
    fun librespeedTemplateUsesMegabyteSizesAndNeverZero() {
        val url = SpeedTestServers.LIBRESPEED.downloadUrl(3L * 1024L * 1024L)
        assertEquals("https://librespeed.org/backend/garbage.php?ckSize=3", url)

        // A sub-megabyte request must still ask for one megabyte: "ckSize=0" would return
        // an empty body and the test would report a bogus 0.
        assertTrue(SpeedTestServers.LIBRESPEED.downloadUrl(1024L).endsWith("ckSize=1"))
    }

    @Test
    fun bothBuiltInServersAcceptUploads() {
        assertTrue(SpeedTestServers.CLOUDFLARE.supportsUpload)
        assertTrue(SpeedTestServers.LIBRESPEED.supportsUpload)
    }

    // ------------------------------------------------------------------ candidates

    @Test
    fun autoPreferencePrefersCloudflareAndKeepsLibreSpeedAsFallback() {
        val candidates = SpeedTestServers.candidates(SpeedTestServers.ID_AUTO, "", "")
        assertEquals(2, candidates.size)
        assertEquals(SpeedTestServers.CLOUDFLARE.host, candidates[0].host)
        assertEquals(SpeedTestServers.LIBRESPEED.host, candidates[1].host)
    }

    @Test
    fun explicitPreferenceNeverFallsBackToAnotherHost() {
        val cloudflare = SpeedTestServers.candidates(SpeedTestServers.ID_CLOUDFLARE, "", "")
        assertEquals(listOf(SpeedTestServers.CLOUDFLARE.host), cloudflare.map { it.host })

        val librespeed = SpeedTestServers.candidates(SpeedTestServers.ID_LIBRESPEED, "", "")
        assertEquals(listOf(SpeedTestServers.LIBRESPEED.host), librespeed.map { it.host })
    }

    @Test
    fun customPreferenceUsesTheUserHost() {
        val candidates = SpeedTestServers.candidates(
            SpeedTestServers.ID_CUSTOM,
            "https://speed.mylab.dev/",
            SpeedTestServers.FLAVOUR_CLOUDFLARE
        )
        assertEquals(1, candidates.size)
        assertEquals("speed.mylab.dev", candidates[0].host)
        assertEquals(
            "https://speed.mylab.dev/__down?bytes=1000",
            candidates[0].downloadUrl(1_000L)
        )
    }

    @Test
    fun customLibreSpeedFlavourBuildsBackendUrls() {
        val server = SpeedTestServers.custom(
            "https://speed.mylab.dev",
            SpeedTestServers.FLAVOUR_LIBRESPEED
        )
        assertEquals(
            "https://speed.mylab.dev/backend/garbage.php?ckSize=5",
            server?.downloadUrl(5L * 1024L * 1024L)
        )
        assertEquals("https://speed.mylab.dev/backend/empty.php", server?.uploadUrl)
    }

    @Test
    fun anUnusableCustomUrlFallsBackToTheDefaultHost() {
        assertNull(SpeedTestServers.custom("not a url", SpeedTestServers.FLAVOUR_CLOUDFLARE))
        assertNull(SpeedTestServers.custom("", SpeedTestServers.FLAVOUR_CLOUDFLARE))

        // A typo must not make the feature dead: the default host is used instead.
        val candidates = SpeedTestServers.candidates(
            SpeedTestServers.ID_CUSTOM,
            "ftp://wrong-scheme.example",
            SpeedTestServers.FLAVOUR_CLOUDFLARE
        )
        assertEquals(listOf(SpeedTestServers.CLOUDFLARE.host), candidates.map { it.host })
    }

    @Test
    fun unknownPreferenceBehavesLikeAuto() {
        val candidates = SpeedTestServers.candidates("SOMETHING_ELSE", "", "")
        assertEquals(2, candidates.size)
        assertEquals(SpeedTestServers.CLOUDFLARE, candidates.first())
    }

    // ------------------------------------------------------------------ data estimate

    @Test
    fun estimateScalesWithTheMeasuredSpeed() {
        val slow = TestDataCost.estimateMegabytes(500_000L, 100_000L)      // ~4 Mbit/s down
        val fast = TestDataCost.estimateMegabytes(12_500_000L, 1_250_000L) // ~100 Mbit/s down
        assertTrue("fast lines must warn about more data than slow ones", fast > slow)
        assertTrue(slow >= 1)
        assertTrue(fast >= 60 && fast <= 200)
    }

    @Test
    fun estimateUsesTheRealPhaseDurations() {
        // 1 MB/s for exactly the download budget (8 s), no upload: 8 MB × 1.1 overhead.
        val bytes = TestDataCost.estimateBytes(
            downloadBps = 1_000_000L,
            uploadBps = 0L,
            uploadEnabled = false
        )
        assertEquals((8_000_000L * 1.1).toLong(), bytes)
    }

    @Test
    fun estimateWithoutUploadIgnoresTheUploadBudget() {
        val withUpload = TestDataCost.estimateBytes(3_000_000L, 1_000_000L, uploadEnabled = true)
        val withoutUpload = TestDataCost.estimateBytes(3_000_000L, 1_000_000L, uploadEnabled = false)
        assertTrue(withoutUpload < withUpload)

        // 8 s of 3 MB/s = 24 MB, × 1.1 overhead = 26.4 MB (≈ 25.2 MiB).
        assertEquals((24_000_000L * 1.1).toLong(), withoutUpload)
        assertTrue(withoutUpload in (20L * 1024L * 1024L)..(32L * 1024L * 1024L))
    }

    @Test
    fun estimateNeverDropsBelowTheFloorForAnUnmeasuredDevice() {
        val mb = TestDataCost.estimateMegabytes(downloadBps = null, uploadBps = null)
        assertTrue(mb >= 5)
    }
}
