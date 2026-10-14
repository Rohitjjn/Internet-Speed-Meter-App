package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.services.LiveSpeed
import com.example.services.NotificationText
import com.example.services.Transport
import com.example.util.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The status-bar ticker is the number most users look at. Its two lines are built by a
 * pure function so this test can pin down: both directions, the chosen unit, the real
 * daily total, the transport name and the "paused" suffix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationTextTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun titleCarriesBothDirectionsInTheChosenUnit() {
        val live = LiveSpeed(
            downloadBps = 1_250_000L, uploadBps = 125_000L,
            steadyDownloadBps = 1_250_000L, steadyUploadBps = 125_000L,
            transport = Transport.WIFI
        )
        val (bytesTitle, _) = NotificationText.build(context, live, 0L, SpeedUnit.BYTES)
        val (bitsTitle, _) = NotificationText.build(context, live, 0L, SpeedUnit.BITS)
        // Download and upload are never the same figure, and the unit follows the setting.
        assertTrue(bytesTitle, bytesTitle.contains("1.19 MB/s") || bytesTitle.contains("1.2 MB/s"))
        assertTrue(bytesTitle, bytesTitle.contains("122") && bytesTitle.contains("KB/s"))
        assertTrue(bitsTitle, bitsTitle.contains("Mbps"))
        assertFalse(bytesTitle == bitsTitle)
    }

    @Test
    fun bodyNamesTheTransportAndTodaysTotalAndFlagsAPause() {
        val running = LiveSpeed(transport = Transport.MOBILE, paused = false)
        val (_, runningText) = NotificationText.build(context, running, 150L * 1024L * 1024L, SpeedUnit.BYTES)
        assertTrue(runningText, runningText.contains(context.getString(R.string.network_mobile)))
        assertTrue(runningText, runningText.contains("150"))
        assertFalse(runningText, runningText.contains(context.getString(R.string.notification_paused_suffix)))

        val paused = running.copy(paused = true)
        val (_, pausedText) = NotificationText.build(context, paused, 0L, SpeedUnit.BYTES)
        assertTrue(pausedText, pausedText.endsWith(context.getString(R.string.notification_paused_suffix)))
        assertTrue(pausedText, pausedText.startsWith(context.getString(R.string.network_mobile)))
        // Same two-line shape in both states: a pause never hides the transport or the total.
        assertEquals(runningText.substringBefore(" "), pausedText.substringBefore(" "))
    }
}
