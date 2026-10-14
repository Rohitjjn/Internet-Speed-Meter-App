package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DataUsageRepository
import com.example.data.PreferencesManager
import com.example.data.UsageResetter
import com.example.util.BytesFormatter
import com.example.util.DateFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Live "today" values in MB (real bytes, never simulated). */
data class DataUsage(
    val total: Double,      // in MB
    val download: Double,   // in MB
    val upload: Double      // in MB
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DataUsageRepository(AppDatabase.getDatabase(application).dataUsageDao())
    private val preferencesManager = PreferencesManager(application)

    val mobileData = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))
    val wifiData = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))

    private val _totalUsage = MutableStateFlow(DataUsage(0.0, 0.0, 0.0))
    val totalUsage: StateFlow<DataUsage> = _totalUsage.asStateFlow()

    /** 0.0 .. 1.0 progress towards the daily mobile limit; null when no limit is set. */
    private val _limitProgress = MutableStateFlow<Double?>(null)
    val limitProgress: StateFlow<Double?> = _limitProgress.asStateFlow()

    private val _mobileLimitLabel = MutableStateFlow("")
    val mobileLimitLabel: StateFlow<String> = _mobileLimitLabel.asStateFlow()

    private val _showResetDialog = MutableStateFlow(false)
    val showResetDialog: StateFlow<Boolean> = _showResetDialog.asStateFlow()

    val permissionGranted: StateFlow<Boolean> =
        com.example.services.SpeedMeterService.permissionGrantedFlow

    init {
        // Follow today's row, rolling over automatically at midnight.
        viewModelScope.launch {
            var currentCollectedDate: String? = null
            var activeCollectionJob: Job? = null
            while (true) {
                val today = DateFormatter.day()
                if (activeCollectionJob == null || today != currentCollectedDate) {
                    activeCollectionJob?.cancel()
                    currentCollectedDate = today
                    activeCollectionJob = launch {
                        repository.getDataByDate(today).collect { entity ->
                            if (entity != null) {
                                mobileData.value = DataUsage(
                                    total = BytesFormatter.toMb(entity.mobileTotal),
                                    download = BytesFormatter.toMb(entity.mobileRx),
                                    upload = BytesFormatter.toMb(entity.mobileTx)
                                )
                                wifiData.value = DataUsage(
                                    total = BytesFormatter.toMb(entity.wifiTotal),
                                    download = BytesFormatter.toMb(entity.wifiRx),
                                    upload = BytesFormatter.toMb(entity.wifiTx)
                                )
                            } else {
                                mobileData.value = DataUsage(0.0, 0.0, 0.0)
                                wifiData.value = DataUsage(0.0, 0.0, 0.0)
                            }
                        }
                    }
                }
                delay(5_000L) // recheck date for seamless midnight transition
            }
        }

        // Consolidated total.
        viewModelScope.launch {
            combine(mobileData, wifiData) { mob, wifi ->
                DataUsage(
                    total = mob.total + wifi.total,
                    download = mob.download + wifi.download,
                    upload = mob.upload + wifi.upload
                )
            }.collect { total ->
                _totalUsage.value = total
            }
        }

        // Daily mobile limit progress.
        viewModelScope.launch {
            combine(preferencesManager.mobileLimitFlow, preferencesManager.mobileLimitUnitFlow, mobileData) { limit, unit, mobile ->
                if (limit > 0.0) {
                    val factor = if (unit.uppercase() == "GB") 1024.0 else 1.0
                    val limitMb = limit * factor
                    mobile.total / limitMb to "${trimNumber(limit)} $unit"
                } else {
                    null to ""
                }
            }.collect { (progress, label) ->
                _limitProgress.value = progress?.coerceIn(0.0, 1.0)
                _mobileLimitLabel.value = label
            }
        }
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    fun requestResetTodayUsage() {
        _showResetDialog.value = true
    }

    fun dismissResetDialog() {
        _showResetDialog.value = false
    }

    fun confirmResetTodayUsage() {
        _showResetDialog.value = false
        resetTodayUsage()
    }

    fun resetTodayUsage() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            UsageResetter.resetToday(app)
            mobileData.value = DataUsage(0.0, 0.0, 0.0)
            wifiData.value = DataUsage(0.0, 0.0, 0.0)
        }
    }
}
