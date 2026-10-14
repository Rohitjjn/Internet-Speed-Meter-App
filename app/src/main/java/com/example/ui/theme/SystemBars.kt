package com.example.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** The app can be dark while the OS is light (and vice versa). Reapply on every toggle. */
internal fun ComponentActivity.applyMeterSystemBars(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        // On older 3-button devices these scrims preserve contrast; newer gesture bars
        // remain edge-to-edge. SystemUI still owns the notification shade's background.
        navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark }
    )
}
