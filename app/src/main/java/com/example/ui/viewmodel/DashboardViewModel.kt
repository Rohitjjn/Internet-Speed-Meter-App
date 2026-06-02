package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import com.example.data.NetworkStatsHelper
import com.example.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DataUsage(
    val total: Double,      // in MB
    val download: Double,   // in MB
    val upload: Double      // in MB
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val database = AppDatabase.getDatabase(application)
    private val repository = DataUsageRepository(database.dataUsageDao())
    private val networkStatsHelper = NetworkStatsHelper(application)

    // Exposed visual data usage states mapping directly into Room single source of truth
    val mobileDataState = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))
    val wifiDataState = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))

    private val _isAddOnActive = MutableStateFlow(false)
    val isAddOnActive: StateFlow<Boolean> = _isAddOnActive.asStateFlow()

    private val _addOnUsage = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))
    val addOnUsage: StateFlow<DataUsage> = _addOnUsage.asStateFlow()

    private val _showConfirmationDialog = MutableStateFlow(false)
    val showConfirmationDialog: StateFlow<Boolean> = _showConfirmationDialog.asStateFlow()

    private val _showResetConfirmationDialog = MutableStateFlow(false)
    val showResetConfirmationDialog: StateFlow<Boolean> = _showResetConfirmationDialog.asStateFlow()

    private val _totalUsage = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))
    val totalUsage: StateFlow<DataUsage> = _totalUsage.asStateFlow()

    init {
        // Hydrate configuration states and start background ticker sync
        viewModelScope.launch {
            preferencesManager.isAddOnActiveFlow.collect { active ->
                _isAddOnActive.value = active
            }
        }

        // Keep local Compose model states updated directly from today's Database changes (Single Source of Truth)
        viewModelScope.launch {
            var currentCollectedDate: String? = null
            var activeCollectionJob: Job? = null

            while (true) {
                val todayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                if (activeCollectionJob == null || todayString != currentCollectedDate) {
                    activeCollectionJob?.cancel()
                    currentCollectedDate = todayString
                    activeCollectionJob = launch {
                        repository.getDataByDate(todayString).collect { todayEntity ->
                            if (todayEntity != null) {
                                val mobileMb = bytesToMb(todayEntity.mobileDataUsed)
                                val wifiMb = bytesToMb(todayEntity.wifiDataUsed)
                                val addOnMb = bytesToMb(todayEntity.addOnDataUsed)

                                mobileDataState.value = DataUsage(mobileMb, mobileMb * 0.85, mobileMb * 0.15)
                                wifiDataState.value = DataUsage(wifiMb, wifiMb * 0.83, wifiMb * 0.17)
                                _addOnUsage.value = DataUsage(addOnMb, addOnMb * 0.84, addOnMb * 0.16)
                            } else {
                                // Reset to 0 if there are no database entries
                                mobileDataState.value = DataUsage(0.0, 0.0, 0.0)
                                wifiDataState.value = DataUsage(0.0, 0.0, 0.0)
                                _addOnUsage.value = DataUsage(0.0, 0.0, 0.0)
                            }
                        }
                    }
                }
                delay(5000) // Recheck date every 5 seconds to support midnight transition seamlessly
            }
        }

        // Keep grand consolidated sum synced dynamically
        viewModelScope.launch {
            combine(
                mobileDataState,
                wifiDataState,
                _addOnUsage
            ) { mob, wifi, addon ->
                DataUsage(
                    total = mob.total + wifi.total + addon.total,
                    download = mob.download + wifi.download + addon.download,
                    upload = mob.upload + wifi.upload + addon.upload
                )
            }.collect { total ->
                _totalUsage.value = total
            }
        }
    }

    fun requestStartTracking() {
        _showConfirmationDialog.value = true
    }

    fun dismissDialog() {
        _showConfirmationDialog.value = false
    }

    fun confirmStartTracking() {
        _showConfirmationDialog.value = false
        viewModelScope.launch {
            preferencesManager.setAddOnActive(true)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            preferencesManager.saveAddOnActivationDate(today)

            // Setup mobile baseline if permission is present
            if (networkStatsHelper.isPermissionGranted()) {
                val currentMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                preferencesManager.saveAddOnBaseline(currentMobile)
            } else {
                preferencesManager.saveAddOnBaseline(-1L)
            }
        }
    }

    fun stopTracking() {
        viewModelScope.launch {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val existing = repository.getDataByDate(today).first()
            val currentAddOnBytes = existing?.addOnDataUsed ?: 0L
            preferencesManager.saveAddOnSavedToday(currentAddOnBytes, today)

            preferencesManager.setAddOnActive(false)
            preferencesManager.saveAddOnBaseline(0L)
            preferencesManager.saveAddOnActivationDate("")
        }
    }

    fun requestResetTodayUsage() {
        _showResetConfirmationDialog.value = true
    }

    fun dismissResetDialog() {
        _showResetConfirmationDialog.value = false
    }

    fun confirmResetTodayUsage() {
        _showResetConfirmationDialog.value = false
        resetTodayUsage()
    }

    fun resetTodayUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
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

    private fun bytesToMb(bytes: Long): Double {
        return bytes / (1024.0 * 1024.0)
    }
}
