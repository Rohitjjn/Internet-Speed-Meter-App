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

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "speed_data_tracker_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_IS_ADD_ON_ACTIVE = booleanPreferencesKey("is_add_on_active")
        val KEY_ADD_ON_DOWNLOAD = doublePreferencesKey("add_on_download")
        val KEY_ADD_ON_UPLOAD = doublePreferencesKey("add_on_upload")
        val KEY_ADD_ON_BASELINE = longPreferencesKey("add_on_baseline")
        
        val KEY_IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val KEY_MOBILE_LIMIT = doublePreferencesKey("mobile_limit")
        val KEY_MOBILE_LIMIT_UNIT = stringPreferencesKey("mobile_limit_unit")
        val KEY_ADDON_LIMIT = doublePreferencesKey("addon_limit")
        val KEY_ADDON_LIMIT_UNIT = stringPreferencesKey("addon_limit_unit")
        
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_TODAY_MOBILE_OFFSET = longPreferencesKey("today_mobile_offset")
        val KEY_TODAY_WIFI_OFFSET = longPreferencesKey("today_wifi_offset")
        val KEY_TODAY_OFFSET_DATE = stringPreferencesKey("today_offset_date")
        val KEY_ADD_ON_ACTIVATION_DATE = stringPreferencesKey("add_on_activation_date")
        val KEY_ADD_ON_SAVED_TODAY = longPreferencesKey("add_on_saved_today")
        val KEY_ADD_ON_SAVED_TODAY_DATE = stringPreferencesKey("add_on_saved_today_date")

        val KEY_LAST_DISMISSED_MOBILE_ALERT = longPreferencesKey("last_dismissed_mobile_alert")
        val KEY_LAST_DISMISSED_ADDON_ALERT = longPreferencesKey("last_dismissed_addon_alert")
        val KEY_LAST_DISMISSED_ALERT_DATE = stringPreferencesKey("last_dismissed_alert_date")
    }

    val lastDismissedMobileAlertFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_DISMISSED_MOBILE_ALERT] ?: 0L
    }

    val lastDismissedAddonAlertFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_DISMISSED_ADDON_ALERT] ?: 0L
    }

    val lastDismissedAlertDateFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_DISMISSED_ALERT_DATE] ?: ""
    }

    suspend fun saveDismissedAlerts(mobileLevel: Long, addonLevel: Long, date: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_DISMISSED_MOBILE_ALERT] = mobileLevel
            preferences[KEY_LAST_DISMISSED_ADDON_ALERT] = addonLevel
            preferences[KEY_LAST_DISMISSED_ALERT_DATE] = date
        }
    }

    val isOnboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    val addOnActivationDateFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_ACTIVATION_DATE] ?: ""
    }

    suspend fun saveAddOnActivationDate(date: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADD_ON_ACTIVATION_DATE] = date
        }
    }

    val todayMobileOffsetFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_TODAY_MOBILE_OFFSET] ?: 0L
    }

    val todayWifiOffsetFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_TODAY_WIFI_OFFSET] ?: 0L
    }

    val todayOffsetDateFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_TODAY_OFFSET_DATE] ?: ""
    }

    suspend fun saveTodayOffsets(mobileOffset: Long, wifiOffset: Long, date: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_TODAY_MOBILE_OFFSET] = mobileOffset
            preferences[KEY_TODAY_WIFI_OFFSET] = wifiOffset
            preferences[KEY_TODAY_OFFSET_DATE] = date
        }
    }

    suspend fun resetTodayOffsets() {
        context.dataStore.edit { preferences ->
            preferences[KEY_TODAY_MOBILE_OFFSET] = 0L
            preferences[KEY_TODAY_WIFI_OFFSET] = 0L
            preferences[KEY_TODAY_OFFSET_DATE] = ""
        }
    }

    val isDarkModeFlow: Flow<Boolean?> = context.dataStore.data.map { preferences ->
        preferences[KEY_IS_DARK_MODE]
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_DARK_MODE] = enabled
        }
    }

    val mobileLimitFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[KEY_MOBILE_LIMIT] ?: 2.0 // Default 2.0
    }

    val mobileLimitUnitFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_MOBILE_LIMIT_UNIT] ?: "GB" // Default GB
    }

    val addonLimitFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADDON_LIMIT] ?: 500.0 // Default 500.0
    }

    val addonLimitUnitFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADDON_LIMIT_UNIT] ?: "MB" // Default MB
    }

    suspend fun saveMobileLimit(limit: Double, unit: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MOBILE_LIMIT] = limit
            preferences[KEY_MOBILE_LIMIT_UNIT] = unit
        }
    }

    suspend fun saveAddonLimit(limit: Double, unit: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADDON_LIMIT] = limit
            preferences[KEY_ADDON_LIMIT_UNIT] = unit
        }
    }

    val addOnBaselineFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_BASELINE] ?: 0L
    }

    suspend fun saveAddOnBaseline(bytes: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADD_ON_BASELINE] = bytes
        }
    }

    val isAddOnActiveFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_IS_ADD_ON_ACTIVE] ?: false
    }

    val addOnDownloadFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_DOWNLOAD] ?: 0.0
    }

    val addOnUploadFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_UPLOAD] ?: 0.0
    }

    suspend fun setAddOnActive(active: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_IS_ADD_ON_ACTIVE] = active
        }
    }

    suspend fun saveAddOnUsage(download: Double, upload: Double) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADD_ON_DOWNLOAD] = download
            preferences[KEY_ADD_ON_UPLOAD] = upload
        }
    }

    val addOnSavedTodayFlow: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_SAVED_TODAY] ?: 0L
    }

    val addOnSavedTodayDateFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_ADD_ON_SAVED_TODAY_DATE] ?: ""
    }

    suspend fun saveAddOnSavedToday(bytes: Long, date: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADD_ON_SAVED_TODAY] = bytes
            preferences[KEY_ADD_ON_SAVED_TODAY_DATE] = date
        }
    }

    suspend fun resetAddOnUsage() {
        context.dataStore.edit { preferences ->
            preferences[KEY_ADD_ON_DOWNLOAD] = 0.0
            preferences[KEY_ADD_ON_UPLOAD] = 0.0
            preferences[KEY_ADD_ON_SAVED_TODAY] = 0L
            preferences[KEY_ADD_ON_SAVED_TODAY_DATE] = ""
        }
    }
}
