package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "speed_meter_prefs")

/**
 * Baseline captured when the user resets today's counters.
 * Daily usage = max(0, systemSinceMidnight - baseline) while the baseline date matches today.
 */
data class ResetOffsets(
    val date: String = "",
    val mobileRx: Long = 0L,
    val mobileTx: Long = 0L,
    val wifiRx: Long = 0L,
    val wifiTx: Long = 0L
) {
    fun appliesTo(day: String): Boolean = date == day
}

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val KEY_MOBILE_LIMIT = doublePreferencesKey("mobile_limit")
        val KEY_MOBILE_LIMIT_UNIT = stringPreferencesKey("mobile_limit_unit")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_RESET_DATE = stringPreferencesKey("reset_date")
        val KEY_RESET_MOBILE_RX = longPreferencesKey("reset_mobile_rx")
        val KEY_RESET_MOBILE_TX = longPreferencesKey("reset_mobile_tx")
        val KEY_RESET_WIFI_RX = longPreferencesKey("reset_wifi_rx")
        val KEY_RESET_WIFI_TX = longPreferencesKey("reset_wifi_tx")
        val KEY_LAST_DISMISSED_MOBILE_ALERT = longPreferencesKey("last_dismissed_mobile_alert")
        val KEY_LAST_DISMISSED_ALERT_DATE = stringPreferencesKey("last_dismissed_alert_date")
    }

    // ---------- Theme ----------

    val isDarkModeFlow: Flow<Boolean?> = context.dataStore.data.map { it[KEY_IS_DARK_MODE] }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_IS_DARK_MODE] = enabled }
    }

    // ---------- Daily mobile data limit (alerts) ----------

    val mobileLimitFlow: Flow<Double> = context.dataStore.data.map { it[KEY_MOBILE_LIMIT] ?: 0.0 }

    val mobileLimitUnitFlow: Flow<String> = context.dataStore.data.map { it[KEY_MOBILE_LIMIT_UNIT] ?: "GB" }

    suspend fun saveMobileLimit(limit: Double, unit: String) {
        context.dataStore.edit {
            it[KEY_MOBILE_LIMIT] = limit
            it[KEY_MOBILE_LIMIT_UNIT] = unit
        }
    }

    // ---------- Onboarding ----------

    val isOnboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    // ---------- "Reset today" baselines ----------

    val resetOffsetsFlow: Flow<ResetOffsets> = context.dataStore.data.map { prefs ->
        ResetOffsets(
            date = prefs[KEY_RESET_DATE] ?: "",
            mobileRx = prefs[KEY_RESET_MOBILE_RX] ?: 0L,
            mobileTx = prefs[KEY_RESET_MOBILE_TX] ?: 0L,
            wifiRx = prefs[KEY_RESET_WIFI_RX] ?: 0L,
            wifiTx = prefs[KEY_RESET_WIFI_TX] ?: 0L
        )
    }

    suspend fun saveResetOffsets(offsets: ResetOffsets) {
        context.dataStore.edit {
            it[KEY_RESET_DATE] = offsets.date
            it[KEY_RESET_MOBILE_RX] = offsets.mobileRx
            it[KEY_RESET_MOBILE_TX] = offsets.mobileTx
            it[KEY_RESET_WIFI_RX] = offsets.wifiRx
            it[KEY_RESET_WIFI_TX] = offsets.wifiTx
        }
    }

    suspend fun clearResetOffsets() {
        context.dataStore.edit {
            it[KEY_RESET_DATE] = ""
            it[KEY_RESET_MOBILE_RX] = 0L
            it[KEY_RESET_MOBILE_TX] = 0L
            it[KEY_RESET_WIFI_RX] = 0L
            it[KEY_RESET_WIFI_TX] = 0L
        }
    }

    // ---------- In-app alert dismissal (daily limit) ----------

    val lastDismissedMobileAlertFlow: Flow<Long> = context.dataStore.data.map {
        it[KEY_LAST_DISMISSED_MOBILE_ALERT] ?: 0L
    }

    val lastDismissedAlertDateFlow: Flow<String> = context.dataStore.data.map {
        it[KEY_LAST_DISMISSED_ALERT_DATE] ?: ""
    }

    suspend fun saveDismissedMobileAlert(level: Long, date: String) {
        context.dataStore.edit {
            it[KEY_LAST_DISMISSED_MOBILE_ALERT] = level
            it[KEY_LAST_DISMISSED_ALERT_DATE] = date
        }
    }
}
