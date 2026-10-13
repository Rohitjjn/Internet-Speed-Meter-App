package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.data.ResetOffsets
import com.example.data.SpeedTestResult
import com.example.util.SpeedUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore round-trip test: every setting the user can change must survive a write →
 * read cycle, because a preference that silently resets is worse than no setting.
 *
 * Robolectric gives a real Context + filesystem, so this exercises the actual
 * `preferencesDataStore` delegate (no mocks, no fake DataStore).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreferencesManagerTest {

    private lateinit var preferences: PreferencesManager

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        preferences = PreferencesManager(application)
    }

    @Test
    fun speedUnitRoundTrips() = runTest {
        preferences.setSpeedUnit(SpeedUnit.BITS)
        assertEquals(SpeedUnit.BITS, preferences.speedUnitFlow.first())

        preferences.setSpeedUnit(SpeedUnit.BYTES)
        assertEquals(SpeedUnit.BYTES, preferences.speedUnitFlow.first())
    }

    @Test
    fun accentAndThemeRoundTrip() = runTest {
        preferences.setAccentKey("VIOLET")
        assertEquals("VIOLET", preferences.accentKeyFlow.first())

        preferences.setDarkMode(true)
        assertEquals(true, preferences.isDarkModeFlow.first())
        preferences.setDarkMode(false)
        assertEquals(false, preferences.isDarkModeFlow.first())

        preferences.setOnboardingCompleted(true)
        assertTrue(preferences.isOnboardingCompletedFlow.first())
    }

    @Test
    fun mobileLimitRoundTripsWithItsUnit() = runTest {
        preferences.saveMobileLimit(1.5, "GB")
        assertEquals(1.5, preferences.mobileLimitFlow.first(), 0.0001)
        assertEquals("GB", preferences.mobileLimitUnitFlow.first())
    }

    @Test
    fun resetBaselineRoundTrips() = runTest {
        val offsets = ResetOffsets("2026-10-13", mobileRx = 4_096L, mobileTx = 512L, wifiRx = 9L, wifiTx = 1L)
        preferences.saveResetOffsets(offsets)
        assertEquals(offsets, preferences.resetOffsetsFlow.first())

        preferences.clearResetOffsets()
        assertEquals(ResetOffsets(), preferences.resetOffsetsFlow.first())
    }

    @Test
    fun lastKnownSpeedIsClampedAndPersisted() = runTest {
        preferences.saveLastSpeed(12_345L, -5L)
        val (down, up) = preferences.lastSpeedFlow.first()
        assertEquals(12_345L, down)
        assertEquals(0L, up) // a negative counter reading must never be persisted
    }

    @Test
    fun speedTestServerPreferenceRoundTrips() = runTest {
        preferences.setSpeedTestServer("HETZNER")
        assertEquals("HETZNER", preferences.speedTestServerFlow.first())
        preferences.setSpeedTestServer("AUTO")
        assertEquals("AUTO", preferences.speedTestServerFlow.first())
    }

    @Test
    fun speedTestHistoryIsPersistedNewestFirstAndCapped() = runTest {
        repeat(3) { index ->
            preferences.saveSpeedTestResult(
                SpeedTestResult(
                    atMillis = 1_000L + index,
                    downloadBps = 1.0 * index,
                    uploadBps = 0.5 * index,
                    latencyMs = 20L + index,
                    jitterMs = 3L,
                    server = "speed.cloudflare.com",
                    uploadServer = "speed.cloudflare.com",
                    bytesUsed = 1_000L * index
                ),
                keep = 2
            )
        }
        val stored = preferences.speedTestResultsFlow.first()
        assertEquals(2, stored.size)
        assertEquals(2L, stored.first().atMillis) // newest first
        assertEquals(2_000L, stored.first().bytesUsed)

        preferences.clearSpeedTestResults()
        assertTrue(preferences.speedTestResultsFlow.first().isEmpty())
    }

    @Test
    fun defaultsAreSaneOnAFreshInstall() = runTest {
        assertEquals(SpeedUnit.BYTES, preferences.speedUnitFlow.first())
        assertEquals("INK", preferences.accentKeyFlow.first())
        assertEquals("AUTO", preferences.speedTestServerFlow.first())
        assertEquals(0.0, preferences.mobileLimitFlow.first(), 0.0)
        assertFalse(preferences.isOnboardingCompletedFlow.first())
        assertTrue(preferences.speedTestResultsFlow.first().isEmpty())
    }
}
