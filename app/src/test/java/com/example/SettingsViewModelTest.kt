package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import com.example.services.MeterControl
import com.example.services.SpeedTestServers
import com.example.ui.viewmodel.SettingsViewModel
import com.example.util.SpeedUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings writes go straight to DataStore and the screen highlights whatever is
 * *stored* — so "tap an endpoint option" must be a durable save, and the custom URL /
 * flavour must survive switching to a built-in option and back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var isolated: TestPreferences
    private lateinit var preferences: PreferencesManager
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        // Own DataStore file per test (no shared singleton to restore afterwards).
        isolated = TestPreferences(application)
        preferences = isolated.manager
        viewModel = SettingsViewModel(application, preferences)
    }

    @After
    fun tearDown() {
        isolated.close()
        Dispatchers.resetMain()
    }

    @Test
    fun tappingAnEndpointOptionIsADurableSave() = runTest(dispatcher) {
        viewModel.updateTestServer(SpeedTestServers.ID_LIBRESPEED, "", SpeedTestServers.FLAVOUR_CLOUDFLARE)
        assertEquals(SpeedTestServers.ID_LIBRESPEED, preferences.testServerFlow.first { it == SpeedTestServers.ID_LIBRESPEED })

        viewModel.updateTestServer(SpeedTestServers.ID_AUTO, "", SpeedTestServers.FLAVOUR_CLOUDFLARE)
        assertEquals(SpeedTestServers.ID_AUTO, preferences.testServerFlow.first { it == SpeedTestServers.ID_AUTO })
    }

    @Test
    fun customUrlAndFlavourSurviveSwitchingAwayAndBack() = runTest(dispatcher) {
        viewModel.updateTestServer(
            SpeedTestServers.ID_CUSTOM,
            "https://speed.example.org",
            SpeedTestServers.FLAVOUR_LIBRESPEED
        )
        preferences.testServerFlow.first { it == SpeedTestServers.ID_CUSTOM }
        assertEquals("https://speed.example.org", preferences.testServerUrlFlow.first())
        assertEquals(SpeedTestServers.FLAVOUR_LIBRESPEED, preferences.testServerFlavourFlow.first())

        // The screen passes the draft URL/flavour along when a built-in option is tapped,
        // so nothing the user typed is lost.
        viewModel.updateTestServer(
            SpeedTestServers.ID_CLOUDFLARE,
            "https://speed.example.org",
            SpeedTestServers.FLAVOUR_LIBRESPEED
        )
        preferences.testServerFlow.first { it == SpeedTestServers.ID_CLOUDFLARE }
        assertEquals("https://speed.example.org", preferences.testServerUrlFlow.first())

        val candidates = SpeedTestServers.candidates(
            preferences.testServerFlow.first(),
            preferences.testServerUrlFlow.first(),
            preferences.testServerFlavourFlow.first()
        )
        assertEquals(listOf(SpeedTestServers.CLOUDFLARE), candidates)
    }

    @Test
    fun pausingTheMeterIsADurableFlagThatEveryRestartPathReads() = runTest(dispatcher) {
        MeterControl.resetCacheForTests()
        viewModel.setMeterPaused(true)
        assertEquals(true, preferences.meterPausedByUserFlow.first { it })
        // The in-memory mirror (dashboard button, tile) follows the store.
        assertEquals(true, MeterControl.pausedByUser.value)
        assertEquals(false, MeterControl.mayStart(application, preferences))

        viewModel.setMeterPaused(false)
        assertEquals(false, preferences.meterPausedByUserFlow.first { !it })
        assertEquals(true, MeterControl.mayStart(application, preferences))
    }

    @Test
    fun monthlyLimitAndReducedMotionWritesRoundTrip() = runTest(dispatcher) {
        viewModel.updateMonthlyLimit(40.0, "GB")
        assertEquals(40.0, preferences.monthlyLimitFlow.first { it == 40.0 }, 0.0)
        assertEquals("GB", preferences.monthlyLimitUnitFlow.first())

        viewModel.setReducedMotion(true)
        assertEquals(true, preferences.reducedMotionFlow.first { it })
    }

    @Test
    fun unitAndLimitWritesRoundTrip() = runTest(dispatcher) {
        viewModel.updateSpeedUnit(SpeedUnit.BITS)
        assertEquals(SpeedUnit.BITS, preferences.speedUnitFlow.first { it == SpeedUnit.BITS })

        viewModel.updateMobileLimit(1.5, "GB")
        assertEquals(1.5, preferences.mobileLimitFlow.first { it == 1.5 }, 0.0)
        assertEquals("GB", preferences.mobileLimitUnitFlow.first())
    }
}
