package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.NetworkStatsHelper
import com.example.data.PreferencesManager
import com.example.data.UsageResetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val applicationContext = application

    val isDarkMode: StateFlow<Boolean?> = preferencesManager.isDarkModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mobileLimit: StateFlow<Double?> = preferencesManager.mobileLimitFlow
        .map { if (it > 0.0) it else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mobileLimitUnit: StateFlow<String> = preferencesManager.mobileLimitUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "GB")

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDarkMode(enabled) }
    }

    fun updateMobileLimit(limit: Double, unit: String) {
        viewModelScope.launch { preferencesManager.saveMobileLimit(limit, unit) }
    }

    fun resetTodayUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            UsageResetter.resetToday(applicationContext)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            UsageResetter.clearAll(applicationContext)
        }
    }
}
