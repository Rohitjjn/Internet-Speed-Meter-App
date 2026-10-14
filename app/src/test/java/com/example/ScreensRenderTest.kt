package com.example

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.example.services.Connectivity
import com.example.services.LatencyResult
import com.example.services.LiveSpeed
import com.example.services.SpeedTestRunner
import com.example.services.SpeedTestServers
import com.example.services.TestAbortSignal
import com.example.services.TestServer
import com.example.services.ThroughputResult
import com.example.services.Transport
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SpeedTestScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose render tests for the two screens that were rewritten without a
 * toolchain: they must compose without crashing, the collapsible Settings groups must
 * open, and tapping an endpoint option must be a durable save (it writes DataStore and the
 * highlighted option follows the *stored* value).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreensRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var isolated: TestPreferences

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        isolated = TestPreferences(application)
    }

    @After
    fun tearDown() {
        isolated.close()
    }

    @Test
    fun settingsRendersAndTheServerGroupExpandsAndSavesOnTap() {
        val viewModel = SettingsViewModel(application, isolated.manager)
        composeRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                SettingsScreen(viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag("settings_screen_root").assertExists()
        composeRule.onNodeWithTag("theme_card").assertExists()
        composeRule.onNodeWithTag("limits_card").assertExists()
        composeRule.onNodeWithTag("permissions_card").assertExists()
        composeRule.onNodeWithTag("test_server_card").assertExists()
        // Collapsed by default: the saved summary is visible, the options are not.
        composeRule.onNodeWithTag("saved_server_label").assertExists()
        composeRule.onNodeWithTag("server_option_librespeed").assertDoesNotExist()

        composeRule.onNodeWithTag("test_server_header").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("server_option_librespeed").assertExists()

        // Tap = save: the stored preference changes, no extra "save" step.
        composeRule.onNodeWithTag("server_option_librespeed").performSemanticsAction(SemanticsActions.OnClick)
        val stored = runBlocking { isolated.manager.testServerFlow.first { it == SpeedTestServers.ID_LIBRESPEED } }
        assertEquals(SpeedTestServers.ID_LIBRESPEED, stored)
    }

    @Test
    fun speedTestIdleRendersGaugeAndStartButtonWithoutANetwork() {
        val viewModel = SpeedTestViewModel(
            application = application,
            connectivity = object : Connectivity {
                override fun currentTransport(): Transport = Transport.WIFI
                override fun isMetered(): Boolean = false
                override fun isDataSaverOn(): Boolean = false
            },
            runner = NeverRunner,
            preferencesManager = isolated.manager,
            liveSpeed = { LiveSpeed() }
        )
        composeRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                SpeedTestScreen(viewModel = viewModel)
            }
        }

        composeRule.onNodeWithTag("speed_test_screen_root").assertExists()
        composeRule.onNodeWithTag("speed_test_gauge_card").assertExists()
        composeRule.onNodeWithTag("speed_test_phase_label").assertExists()
        composeRule.onNodeWithTag("speed_test_gauge_number").assertExists()
        composeRule.onNodeWithTag("speed_test_start_button").assertExists()
        // Idle = nothing measured yet: no ghost legend, no result card, no error.
        composeRule.onNodeWithTag("speed_test_ghost_legend").assertDoesNotExist()
        composeRule.onNodeWithTag("speed_test_share_button").assertDoesNotExist()
    }

    /** A runner that must never be reached by a render test. */
    private object NeverRunner : SpeedTestRunner {
        override suspend fun latency(server: TestServer, abort: TestAbortSignal, onSample: (Long) -> Unit): LatencyResult =
            error("render test must not measure")

        override suspend fun download(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult =
            error("render test must not measure")

        override suspend fun upload(server: TestServer, abort: TestAbortSignal, onProgress: (Double, Float) -> Unit): ThroughputResult =
            error("render test must not measure")
    }
}
