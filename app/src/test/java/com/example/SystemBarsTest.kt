package com.example

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.example.ui.theme.applyMeterSystemBars
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 29, 35])
class SystemBarsTest {
    @Test
    fun appThemeControlsStatusAndNavigationIconContrastInBothDirections() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            for (dark in listOf(true, false, true)) {
                activity.applyMeterSystemBars(dark)
                assertEquals(!dark, insets.isAppearanceLightStatusBars)
                assertEquals(!dark, insets.isAppearanceLightNavigationBars)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
