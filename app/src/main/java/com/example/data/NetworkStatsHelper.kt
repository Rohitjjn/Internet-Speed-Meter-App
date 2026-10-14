package com.example.data

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Process
import java.util.Calendar

/** Bytes transferred on one transport since a reference time. */
data class TransportBytes(
    val rx: Long = 0L,
    val tx: Long = 0L
) {
    val total: Long get() = rx + tx

    operator fun plus(other: TransportBytes) = TransportBytes(rx + other.rx, tx + other.tx)

    /** Clamps to zero so a stale "reset" baseline can never push usage negative. */
    operator fun minus(other: TransportBytes) =
        TransportBytes((rx - other.rx).coerceAtLeast(0L), (tx - other.tx).coerceAtLeast(0L))

    val isZero: Boolean get() = rx == 0L && tx == 0L
}

/**
 * Reads REAL system-wide per-transport counters via NetworkStatsManager.
 * Requires the special "Usage access" permission (PACKAGE_USAGE_STATS).
 */
class NetworkStatsHelper(private val context: Context) {

    fun isPermissionGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Mobile (cellular) bytes since midnight. */
    fun getMobileSinceMidnight(): TransportBytes = getBytesSinceMidnight(NetworkCapabilities.TRANSPORT_CELLULAR)

    /** Wi-Fi bytes since midnight. */
    fun getWifiSinceMidnight(): TransportBytes = getBytesSinceMidnight(NetworkCapabilities.TRANSPORT_WIFI)

    /** Ethernet bytes since midnight. */
    fun getEthernetSinceMidnight(): TransportBytes = getBytesSinceMidnight(NetworkCapabilities.TRANSPORT_ETHERNET)

    /** Wi-Fi + Ethernet combined (what users think of as "home network"). */
    fun getWifiOrEthernetSinceMidnight(): TransportBytes =
        getWifiSinceMidnight() + getEthernetSinceMidnight()

    private fun getBytesSinceMidnight(transport: Int): TransportBytes {
        if (!isPermissionGranted()) return TransportBytes()
        return try {
            val statsManager =
                context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                    ?: return TransportBytes()
            val start = getMidnightTimeInMillis()
            val end = System.currentTimeMillis()
            val bucket = statsManager.querySummaryForDevice(transport, null, start, end)
            TransportBytes(bucket.rxBytes, bucket.txBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            TransportBytes()
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
