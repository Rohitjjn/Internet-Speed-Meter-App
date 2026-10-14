package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesManager
import com.example.data.UsageResetter
import com.example.services.SpeedTestServers
import com.example.util.SpeedUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val preferencesManager: PreferencesManager
) : AndroidViewModel(application) {

    /** The constructor the default `ViewModelProvider` factory uses. */
    constructor(application: Application) : this(application, PreferencesManager(application))

    private val applicationContext = application

    val isDarkMode: StateFlow<Boolean?> = preferencesManager.isDarkModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mobileLimit: StateFlow<Double?> = preferencesManager.mobileLimitFlow
        .map { if (it > 0.0) it else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mobileLimitUnit: StateFlow<String> = preferencesManager.mobileLimitUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "GB")

    val speedUnit: StateFlow<SpeedUnit> = preferencesManager.speedUnitFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeedUnit.BYTES)

    val accentKey: StateFlow<String> = preferencesManager.accentKeyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "INK")

    /** Which endpoint the opt-in active speed test measures against. */
    val testServer: StateFlow<String> = preferencesManager.testServerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeedTestServers.ID_AUTO)

    val testServerUrl: StateFlow<String> = preferencesManager.testServerUrlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val testServerFlavour: StateFlow<String> = preferencesManager.testServerFlavourFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpeedTestServers.FLAVOUR_CLOUDFLARE)

    fun toggleDarkMode(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDarkMode(enabled) }
    }

    fun updateMobileLimit(limit: Double, unit: String) {
        viewModelScope.launch { preferencesManager.saveMobileLimit(limit, unit) }
    }

    fun updateSpeedUnit(unit: SpeedUnit) {
        viewModelScope.launch { preferencesManager.setSpeedUnit(unit) }
    }

    fun updateAccent(key: String) {
        viewModelScope.launch { preferencesManager.setAccentKey(key) }
    }

    fun updateTestServer(preference: String, customUrl: String, flavour: String) {
        viewModelScope.launch { preferencesManager.saveTestServer(preference, customUrl, flavour) }
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
