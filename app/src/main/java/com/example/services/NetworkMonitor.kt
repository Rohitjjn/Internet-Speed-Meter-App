package com.example.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

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

    /** True when the active transport is billed per megabyte (mobile data). */
    fun isMetered(context: Context): Boolean = currentTransport(context) == Transport.MOBILE

    /** Human label used in messages ("Wi-Fi", "Mobile data", …). */
    fun labelOf(transport: Transport): String = when (transport) {
        Transport.WIFI -> "Wi-Fi"
        Transport.MOBILE -> "Mobile data"
        Transport.ETHERNET -> "Ethernet"
        Transport.VPN -> "VPN"
        Transport.NONE -> "no network"
    }
}
