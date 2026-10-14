package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.data.ResetOffsets
import com.example.data.SpeedTestResult
import com.example.services.SpeedTestServers
import com.example.util.SpeedUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore round-trips. The stored state is what the meter restarts from (reset baseline,
 * last speed, endpoint choice), so a broken round-trip silently changes the numbers the
 * user sees after a reboot — worth a test even though it is "just" persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreferencesManagerTest {

    private lateinit var isolated: TestPreferences
    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // Own DataStore file per test: defaults are really defaults, nothing leaks out.
        isolated = TestPreferences(application)
        preferences = isolated.manager
    }

    @After
    fun tearDown() {
        isolated.close()
    }

    @Test
    fun speedUnitAndAccentRoundTrip() = runTest {
        preferences.setSpeedUnit(SpeedUnit.BITS)
        assertEquals(SpeedUnit.BITS, preferences.speedUnitFlow.first())

        preferences.setSpeedUnit(SpeedUnit.BYTES)
        assertEquals(SpeedUnit.BYTES, preferences.speedUnitFlow.first())

        preferences.setAccentKey("VIOLET")
        assertEquals("VIOLET", preferences.accentKeyFlow.first())
    }

    @Test
    fun resetBaselineRoundTripsAndClears() = runTest {
        val offsets = ResetOffsets(
            date = "2026-10-13",
            mobileRx = 111L,
            mobileTx = 222L,
            wifiRx = 333L,
            wifiTx = 444L
        )
        preferences.saveResetOffsets(offsets)
        assertEquals(offsets, preferences.resetOffsetsFlow.first())

        preferences.clearResetOffsets()
        val cleared = preferences.resetOffsetsFlow.first()
        assertEquals("", cleared.date)
        assertFalse(cleared.appliesTo("2026-10-13"))
        assertEquals(0L, cleared.mobileRx)
    }

    @Test
    fun lastSpeedSurvivesWithNonNegativeValues() = runTest {
        preferences.saveLastSpeed(9_000L, 1_500L)
        val restored = preferences.lastSpeedFlow.first()
        assertEquals(9_000L, restored.first)
        assertEquals(1_500L, restored.second)

        // A negative value can only be a bug upstream; it must not be persisted as such.
        preferences.saveLastSpeed(-5L, -1L)
        assertEquals(0L, preferences.lastSpeedFlow.first().first)
    }

    @Test
    fun mobileLimitRoundTripsWithItsUnit() = runTest {
        preferences.saveMobileLimit(2.5, "GB")
        assertEquals(2.5, preferences.mobileLimitFlow.first(), 0.0001)
        assertEquals("GB", preferences.mobileLimitUnitFlow.first())
    }

    @Test
    fun speedTestHistoryKeepsOnlyTheNewestTwentyResults() = runTest {
        preferences.clearSpeedTestResults()
        repeat(25) { index ->
            preferences.saveSpeedTestResult(
                SpeedTestResult(
                    atMillis = 1_700_000_000_000L + index,
                    downloadBps = 1_000_000.0 + index,
                    uploadBps = 100_000.0,
                    latencyMs = 20L + index,
                    jitterMs = 3L,
                    server = "speed.cloudflare.com"
                )
            )
        }
        val history = preferences.speedTestResultsFlow.first()
        assertEquals(20, history.size)
        // Newest first: the last saved entry leads, the oldest five are gone.
        assertEquals(1_700_000_000_024L, history.first().atMillis)
        assertEquals(1_700_000_000_005L, history.last().atMillis)
    }

    @Test
    fun theChosenTestEndpointRoundTrips() = runTest {
        assertEquals(SpeedTestServers.ID_AUTO, preferences.testServerFlow.first())

        preferences.saveTestServer(
            preference = SpeedTestServers.ID_CUSTOM,
            customUrl = "https://speed.mylab.dev",
            flavour = SpeedTestServers.FLAVOUR_LIBRESPEED
        )
        assertEquals(SpeedTestServers.ID_CUSTOM, preferences.testServerFlow.first())
        assertEquals("https://speed.mylab.dev", preferences.testServerUrlFlow.first())
        assertEquals(SpeedTestServers.FLAVOUR_LIBRESPEED, preferences.testServerFlavourFlow.first())

        // An unknown/corrupt stored value must still resolve to a working endpoint, never
        // to "no server at all".
        preferences.saveTestServer("nonsense", "", "")
        val candidates = SpeedTestServers.candidates(preferences.testServerFlow.first(), "", "")
        assertEquals(SpeedTestServers.CLOUDFLARE, candidates.first())
        assertTrue(candidates.size > 1)
    }
}
