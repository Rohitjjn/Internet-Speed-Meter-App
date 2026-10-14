package com.example.services

import android.content.Context
import com.example.R
import com.example.util.BytesFormatter
import com.example.util.SpeedFormatter
import com.example.util.SpeedUnit

/**
 * The two lines of the status-bar ticker, built from the engine's snapshot — pulled out of
 * [SpeedMeterService] so the exact text a user sees can be unit-tested without a service:
 * speeds in the chosen unit, the transport name, today's real total and the "paused"
 * suffix whenever the engine flagged the last window as unusable.
 */
object NotificationText {

    /** (title, text) of the ongoing speed notification. */
    fun build(context: Context, live: LiveSpeed, todayTotalBytes: Long, unit: SpeedUnit): Pair<String, String> {
        val networkLabel = context.getString(
            when (live.transport) {
                Transport.WIFI -> R.string.network_wifi
                Transport.ETHERNET -> R.string.network_ethernet
                Transport.VPN -> R.string.network_vpn
                Transport.MOBILE -> R.string.network_mobile
                Transport.NONE -> R.string.network_offline
            }
        )
        val title = context.getString(
            R.string.notification_speed_title,
            SpeedFormatter.format(live.steadyDownloadBps, unit),
            SpeedFormatter.format(live.steadyUploadBps, unit)
        )
        val text = buildString {
            append(context.getString(R.string.notification_text, networkLabel, BytesFormatter.format(todayTotalBytes)))
            if (live.paused) append(context.getString(R.string.notification_paused_suffix))
        }
        return title to text
    }
}
