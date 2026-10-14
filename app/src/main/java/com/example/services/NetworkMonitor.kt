package com.example.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the active speed test needs to know about the connection, behind an interface so
 * the view model's rules (ask before spending metered data, abort on a transport change)
 * can be unit tested with a scripted implementation. [AndroidConnectivity] is the real one.
 */
interface Connectivity {
    /** The transport the device is using *right now*. */
    fun currentTransport(): Transport

    /**
     * True when the current connection is billed per megabyte — cellular, a VPN that rides
     * on cellular, or a Wi-Fi network the user flagged as metered (hotspots).
     */
    fun isMetered(): Boolean

    /** True when the system Data Saver is enabled (shown in the confirmation as a hint). */
    fun isDataSaverOn(): Boolean
}

/** [Connectivity] backed by `ConnectivityManager`. */
class AndroidConnectivity(context: Context) : Connectivity {
    private val appContext = context.applicationContext

    override fun currentTransport(): Transport = NetworkMonitor.currentTransport(appContext)

    override fun isMetered(): Boolean = NetworkMonitor.isMetered(appContext)

    override fun isDataSaverOn(): Boolean = NetworkMonitor.isDataSaverOn(appContext)
}

/**
 * Reads the transport the device is using *right now*.
 *
 * The running meter learns about transport changes from a `NetworkCallback` push
 * (zero binder calls in the hot path), but the active speed test needs a **fresh** answer
 * before and during a test, because a Wi-Fi → mobile switch in the middle of a download
 * would spend the user's metered data and mix two different connections into one result.
 *
 * These calls happen at most twice per second and only while a test runs.
 */
object NetworkMonitor {

    fun currentTransport(context: Context): Transport {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Transport.NONE
            val network = cm.activeNetwork ?: return Transport.NONE
            val capabilities = cm.getNetworkCapabilities(network) ?: return Transport.NONE
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.MOBILE
                else -> Transport.NONE
            }
        } catch (e: Exception) {
            Transport.NONE
        }
    }

    /**
     * True when the active connection is billed per megabyte. Cellular is always metered;
     * on top of that the OS flag covers a VPN tunnelled over mobile data and a Wi-Fi
     * network the user marked as metered — both would otherwise spend real data silently.
     */
    fun isMetered(context: Context): Boolean {
        if (currentTransport(context) == Transport.MOBILE) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered == true
        } catch (e: Exception) {
            false
        }
    }

    /** True when the system Data Saver is on (API 24+ — the app's minimum). */
    fun isDataSaverOn(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
        } catch (e: Exception) {
            false
        }
    }
}
