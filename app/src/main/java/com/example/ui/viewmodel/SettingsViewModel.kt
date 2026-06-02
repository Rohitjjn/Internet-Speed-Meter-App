package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.NetworkStatsHelper
import com.example.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val database = AppDatabase.getDatabase(application)
    private val applicationContext = application

    val isDarkMode: StateFlow<Boolean?> = preferencesManager.isDarkModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val mobileLimit: StateFlow<Double?> = preferencesManager.mobileLimitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val mobileLimitUnit: StateFlow<String> = preferencesManager.mobileLimitUnitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "GB"
        )

    val addonLimit: StateFlow<Double?> = preferencesManager.addonLimitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val addonLimitUnit: StateFlow<String> = preferencesManager.addonLimitUnitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "MB"
        )

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkMode(enabled)
        }
    }

    fun updateMobileLimit(limit: Double, unit: String) {
        viewModelScope.launch {
            preferencesManager.saveMobileLimit(limit, unit)
        }
    }

    fun updateAddonLimit(limit: Double, unit: String) {
        viewModelScope.launch {
            preferencesManager.saveAddonLimit(limit, unit)
        }
    }

    fun resetTodayUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val networkStatsHelper = NetworkStatsHelper(applicationContext)
            if (networkStatsHelper.isPermissionGranted()) {
                val rawMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                val rawWifi = networkStatsHelper.getWifiBytesSinceMidnight()
                preferencesManager.saveTodayOffsets(rawMobile, rawWifi, today)
            } else {
                preferencesManager.resetTodayOffsets()
            }
            
            database.dataUsageDao().deleteTodayData(today)
            preferencesManager.resetAddOnUsage()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val networkStatsHelper = NetworkStatsHelper(applicationContext)
            if (networkStatsHelper.isPermissionGranted()) {
                val rawMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                val rawWifi = networkStatsHelper.getWifiBytesSinceMidnight()
                preferencesManager.saveTodayOffsets(rawMobile, rawWifi, today)
            } else {
                preferencesManager.resetTodayOffsets()
            }
            
            database.dataUsageDao().clearAllHistory()
            preferencesManager.resetAddOnUsage()
        }
    }
}
