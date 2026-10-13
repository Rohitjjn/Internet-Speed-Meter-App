package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.services.SpeedTestEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Network-gated smoke test for the **active** speed test.
 *
 * It exists for one reason: the test talks to a third-party public endpoint, and if that
 * endpoint changes its protocol (or disappears) the failure is invisible to unit tests.
 * This runs a short, real 2-second download against the default server and asserts that
 * bytes actually arrived at a plausible rate.
 *
 * It is skipped (not failed) when the device is offline, and it is intentionally NOT part
 * of the CI unit-test job — run it manually on a device with connectivity.
 */
@RunWith(AndroidJUnit4::class)
class SpeedTestSmokeTest {

    @Test
    fun downloadPhaseMeasuresRealBytes() = runBlocking {
        assumeTrue("device is offline — skipping the network smoke test", hasInternet())

        val result = SpeedTestEngine.measureDownload(
            server = SpeedTestEngine.CLOUDFLARE,
            durationMs = 2_000L,
            streams = 2
        )

        assertTrue("the server sent no data", result.bytes > 0L)
        assertTrue("no throughput was measured", result.bitsPerSecond > 0.0)
        assertTrue(
            "2 s at 1 Gbit/s is ~250 MB — anything beyond that is a broken counter",
            result.bytes < 512L * 1024L * 1024L
        )
        assertTrue("at least one stream carried data", result.streams >= 1)
    }

    private fun hasInternet(): Boolean {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
