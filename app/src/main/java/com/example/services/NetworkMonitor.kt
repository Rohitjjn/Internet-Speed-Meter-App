package com.example.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

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

    /**
     * The transport, re-read every time it may have changed, starting with the current
     * one. The default is a plain poll every [pollIntervalMs] (fakes, odd ROMs);
     * [AndroidConnectivity] is **pushed** by `ConnectivityManager` instead and only keeps a
     * slow poll as a safety net.
     */
    fun transportChanges(pollIntervalMs: Long = DEFAULT_POLL_MS): Flow<Transport> = flow {
        while (true) {
            emit(currentTransport())
            delay(pollIntervalMs)
        }
    }

    companion object {
        /** Poll cadence of the default implementation (fakes / fallback). */
        const val DEFAULT_POLL_MS = 700L
    }
}

/** [Connectivity] backed by `ConnectivityManager`. */
class AndroidConnectivity(context: Context) : Connectivity {
    private val appContext = context.applicationContext

    override fun currentTransport(): Transport = NetworkMonitor.currentTransport(appContext)

    override fun isMetered(): Boolean = NetworkMonitor.isMetered(appContext)

    override fun isDataSaverOn(): Boolean = NetworkMonitor.isDataSaverOn(appContext)

    /**
     * Push-based: a default-network callback re-evaluates the transport on every
     * available/lost/capabilities event (zero binder calls while nothing changes), plus one
     * slow poll as a fallback for ROMs that drop callbacks. Unregisters when the collector
     * (the test run) goes away.
     */
    override fun transportChanges(pollIntervalMs: Long): Flow<Transport> = callbackFlow {
        trySend(currentTransport())
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentTransport())
            }

            override fun onLost(network: Network) {
                trySend(currentTransport())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(currentTransport())
            }
        }
        val registered = try {
            cm?.registerDefaultNetworkCallback(callback)
            cm != null
        } catch (e: Exception) {
            false
        }
        val safetyPoll = launch {
            val interval = if (registered) FALLBACK_POLL_MS else pollIntervalMs
            while (true) {
                delay(interval)
                trySend(currentTransport())
            }
        }
        awaitClose {
            safetyPoll.cancel()
            if (registered) {
                try {
                    cm?.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }.conflate()

    private companion object {
        /** Safety poll while the callback is registered — a handful of calls per test. */
        const val FALLBACK_POLL_MS = 2_000L
    }
}

/**
 * Reads the transport the device is using *right now*.
 *
 * The running meter learns about transport changes from a `NetworkCallback` push
 * (zero binder calls in the hot path), but the active speed test needs a **fresh** answer
 * before and during a test, because a Wi-Fi → mobile switch in the middle of a download
 * would spend the user's metered data and mix two different connections into one result.
 *
 * While a test runs the transport is pushed by a `NetworkCallback` (see
 * [AndroidConnectivity.transportChanges]); these direct reads happen a few times per test.
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
