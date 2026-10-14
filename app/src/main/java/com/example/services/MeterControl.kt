package com.example.services

import android.content.Context
import com.example.data.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The user's own pause switch for the meter.
 *
 * Two different things can stop the meter and the app must never confuse them:
 *
 *  - the **OS** refused or ended the foreground service (Android 12+ background-start
 *    rule, Android 15+ `dataSync` budget, an OEM task killer) — the app says "paused",
 *    posts the "Speed meter paused" notice and every restart path keeps trying;
 *  - the **user** switched it off here — the app says "paused by you", posts nothing and
 *    every restart path ([com.example.MainActivity.onResume], the boot receiver, the
 *    watchdog worker, the timeout retry) stays quiet until [resume].
 *
 * The flag lives in DataStore (`meter_paused_by_user`) so it survives reboots; this object
 * mirrors it in memory for the UI and the Quick Settings tile. Nothing is measured or
 * counted while paused — the persisted "today" row is the only number still shown.
 */
object MeterControl {

    private val _pausedByUser = MutableStateFlow(false)

    /** In-memory mirror of the stored flag; valid once [isPausedByUser] has run once. */
    val pausedByUser: StateFlow<Boolean> = _pausedByUser.asStateFlow()

    @Volatile
    private var loaded = false

    /** The stored flag — read from DataStore on first use, then served from memory. */
    suspend fun isPausedByUser(
        context: Context,
        preferences: PreferencesManager = PreferencesManager(context.applicationContext)
    ): Boolean {
        if (!loaded) {
            val stored = try {
                preferences.meterPausedByUserFlow.first()
            } catch (e: Exception) {
                false
            }
            _pausedByUser.value = stored
            loaded = true
        }
        return _pausedByUser.value
    }

    /**
     * What a background restart path should do: `false` means "the user paused the meter,
     * leave it alone". Keeps the decision in one place for the receiver, the worker and the
     * activity so none of them can drift.
     */
    suspend fun mayStart(
        context: Context,
        preferences: PreferencesManager = PreferencesManager(context.applicationContext)
    ): Boolean = !isPausedByUser(context, preferences)

    /** Starts the meter unless the user paused it. Returns whether a start was *accepted*. */
    suspend fun startUnlessPaused(context: Context): Boolean {
        if (!mayStart(context)) return false
        return SpeedMeterService.startService(context)
    }

    /**
     * Stops the meter on the user's request and remembers it. The in-memory mirror flips
     * first (the dashboard button and the tile react at once), then the flag is persisted,
     * then the service is stopped.
     */
    suspend fun pause(
        context: Context,
        preferences: PreferencesManager = PreferencesManager(context.applicationContext)
    ) {
        val app = context.applicationContext
        _pausedByUser.value = true
        loaded = true
        preferences.setMeterPausedByUser(true)
        SpeedMeterService.stopService(app)
        // The OS notice ("Android stopped the meter") is moot while the user holds the pause.
        SpeedMeterService.clearPausedNotification(app)
    }

    /** Forgets the pause and starts the meter again. Returns whether the OS accepted the start. */
    suspend fun resume(
        context: Context,
        preferences: PreferencesManager = PreferencesManager(context.applicationContext)
    ): Boolean {
        val app = context.applicationContext
        _pausedByUser.value = false
        loaded = true
        preferences.setMeterPausedByUser(false)
        return SpeedMeterService.startService(app)
    }

    /** Test hook: forget the cached flag so the next read goes to the store again. */
    internal fun resetCacheForTests() {
        loaded = false
        _pausedByUser.value = false
    }
}
