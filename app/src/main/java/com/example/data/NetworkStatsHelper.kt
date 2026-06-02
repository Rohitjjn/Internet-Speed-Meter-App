package com.example.data

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import java.util.Calendar

class NetworkStatsHelper(private val context: Context) {

    fun isPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } else {
            true
        }
    }

    /**
     * Fetch Mobile (cellular) bytes transferred since midnight.
     */
    fun getMobileBytesSinceMidnight(): Long {
        if (!isPermissionGranted()) return 0L
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return 0L
                val startTime = getMidnightTimeInMillis()
                val endTime = System.currentTimeMillis()

                val bucket = networkStatsManager.querySummaryForDevice(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    null,
                    startTime,
                    endTime
                )
                bucket.rxBytes + bucket.txBytes
            } else {
                0L
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    /**
     * Fetch WiFi bytes transferred since midnight.
     */
    fun getWifiBytesSinceMidnight(): Long {
        if (!isPermissionGranted()) return 0L
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return 0L
                val startTime = getMidnightTimeInMillis()
                val endTime = System.currentTimeMillis()

                val bucket = networkStatsManager.querySummaryForDevice(
                    NetworkCapabilities.TRANSPORT_WIFI,
                    null,
                    startTime,
                    endTime
                )
                bucket.rxBytes + bucket.txBytes
            } else {
                0L
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    /**
     * Fetch Ethernet bytes transferred since midnight.
     */
    fun getEthernetBytesSinceMidnight(): Long {
        if (!isPermissionGranted()) return 0L
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val networkStatsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return 0L
                val startTime = getMidnightTimeInMillis()
                val endTime = System.currentTimeMillis()

                val bucket = networkStatsManager.querySummaryForDevice(
                    NetworkCapabilities.TRANSPORT_ETHERNET,
                    null,
                    startTime,
                    endTime
                )
                bucket.rxBytes + bucket.txBytes
            } else {
                0L
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }

    private fun getMidnightTimeInMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
