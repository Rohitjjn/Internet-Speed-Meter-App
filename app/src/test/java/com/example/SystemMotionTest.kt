package com.example

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.ui.theme.LocalReducedMotion
import com.example.ui.theme.LocalSystemReducedMotion
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.motionDuration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemMotionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun systemChangesAreObservedAndNeverOverwriteTheUserPreference() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val user = mutableStateOf(false)
        var effective = false
        var forced = false
        var duration = 0
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        composeRule.setContent {
            MyApplicationTheme(darkTheme = true, reducedMotion = user.value) {
                duration = motionDuration(520)
                effective = LocalReducedMotion.current
                forced = LocalSystemReducedMotion.current
            }
        }
        composeRule.runOnIdle { assertFalse(effective); assertEquals(520, duration) }
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        composeRule.waitUntil { composeRule.waitForIdle(); forced && effective }
        composeRule.runOnIdle { assertFalse(user.value); assertEquals(0, duration); user.value = true }
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        composeRule.waitUntil { composeRule.waitForIdle(); !forced }
        composeRule.runOnIdle { assertTrue(effective); user.value = false }
        composeRule.runOnIdle { assertFalse(effective); assertEquals(520, duration) }
    }
}
