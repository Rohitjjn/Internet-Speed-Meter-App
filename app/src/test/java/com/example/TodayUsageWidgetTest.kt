package com.example

import android.app.Application
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.data.DataUsageEntity
import com.example.util.BytesFormatter
import com.example.widget.TodayUsageWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The home-screen widget is plain RemoteViews: inflate what the service/worker would hand
 * to the launcher and read the texts back. A wrong id or layout would otherwise only show
 * up as "widget can't load" on a user's home screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayUsageWidgetTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun widgetShowsTodaysRealTotalAndTheSplit() {
        val usage = DataUsageEntity(
            date = "2026-10-14",
            mobileRx = 200L * 1024L * 1024L,
            mobileTx = 56L * 1024L * 1024L,
            wifiRx = 1024L * 1024L * 1024L,
            wifiTx = 0L
        )

        val views = TodayUsageWidget.buildViews(application, usage, Date(0L))
        val root = views.apply(application, FrameLayout(application))

        val total = root.findViewById<TextView>(R.id.widget_total)
        val breakdown = root.findViewById<TextView>(R.id.widget_breakdown)
        val updated = root.findViewById<TextView>(R.id.widget_updated)
        assertNotNull(total)
        assertNotNull(breakdown)
        assertNotNull(updated)

        // Total = mobile + Wi-Fi, formatted by the same formatter the app uses everywhere.
        assertEquals(BytesFormatter.format(usage.total), total.text.toString())
        val split = breakdown.text.toString()
        assertTrue(split, split.contains(BytesFormatter.format(usage.mobileTotal)))
        assertTrue(split, split.contains(BytesFormatter.format(usage.wifiTotal)))
        // The timestamp line is always present: a widget number without "as of" is a lie.
        assertTrue(updated.text.toString().isNotBlank())
        // The whole card opens the app.
        assertTrue(root.findViewById<android.view.View>(R.id.widget_root).hasOnClickListeners())
    }

    @Test
    fun anEmptyDayRendersZeroNotAPlaceholder() {
        val views = TodayUsageWidget.buildViews(application, DataUsageEntity("2026-10-14"), Date(0L))
        val root = views.apply(application, FrameLayout(application))
        assertEquals(BytesFormatter.format(0L), root.findViewById<TextView>(R.id.widget_total).text.toString())
    }
}
