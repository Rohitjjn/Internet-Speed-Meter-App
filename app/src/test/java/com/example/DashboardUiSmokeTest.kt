package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.screens.HeroSpeedCard
import com.example.ui.screens.UsageCard
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DataUsage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric + Compose smoke test: the dashboard's key surfaces must render, and the
 * live card must expose both speed readouts *separately* (the reported bug was that the
 * same number appeared for download and upload).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DashboardUiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun heroCardRendersBothDirectionsAndSessionStats() {
        composeRule.setContent {
            MyApplicationTheme(darkTheme = false) {
                HeroSpeedCard()
            }
        }

        composeRule.onNodeWithTag("live_speed_card").assertExists()
        composeRule.onNodeWithTag("live_download_speed_text").assertExists()
        composeRule.onNodeWithTag("live_upload_speed_text").assertExists()
        composeRule.onNodeWithTag("live_speed_status_label").assertExists()
        composeRule.onNodeWithText("DOWNLOAD").assertExists()
        composeRule.onNodeWithText("UPLOAD").assertExists()
        composeRule.onNodeWithText("1 MIN AVG").assertExists()
        composeRule.onNodeWithText("THIS SESSION").assertExists()
    }

    @Test
    fun usageCardShowsRealDailySplit() {
        composeRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                UsageCard(
                    title = "Mobile Data",
                    iconRes = R.drawable.ic_cellular,
                    data = DataUsage(total = 256.0, download = 240.0, upload = 16.0)
                )
            }
        }

        composeRule.onNodeWithText("Mobile Data").assertExists()
        composeRule.onNodeWithText("TODAY").assertExists()
        composeRule.onNodeWithText("256.0 MB").assertExists()
        composeRule.onNodeWithText("DOWN").assertExists()
        composeRule.onNodeWithText("UP").assertExists()
    }
}
