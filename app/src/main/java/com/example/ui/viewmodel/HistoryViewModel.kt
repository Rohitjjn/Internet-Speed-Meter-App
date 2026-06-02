package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DataUsageRepository

    val historyList: StateFlow<List<DataUsageEntity>>
    val monthlyTotalBytes: StateFlow<Long>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DataUsageRepository(database.dataUsageDao())

        val currentMonthPrefix = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date()) + "-%"

        historyList = repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        monthlyTotalBytes = repository.getThisMonthTotal(currentMonthPrefix)
            .map { it ?: 0L }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0L
            )

        // Seed sample telemetry records on clean installation
        viewModelScope.launch {
            try {
                val existing = repository.allHistory.first()
                if (existing.isEmpty()) {
                    populateDummyData()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun populateDummyData() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        // Start seeding from yesterday to avoid clashing/duplicating today's real tracking record
        cal.add(Calendar.DAY_OF_YEAR, -1)

        // Create 14 days of historic telemetry backbeats (excluding today)
        for (i in 0 until 14) {
            val dateString = sdf.format(cal.time)

            val mobileBytes = (120..680).random().toLong() * 1024L * 1024L
            val wifiBytes = (600..2800).random().toLong() * 1024L * 1024L
            // Give every third day some active Add-On usage
            val addOnBytes = if (i % 3 == 0) {
                (40..320).random().toLong() * 1024L * 1024L
            } else {
                0L
            }

            repository.insertData(
                DataUsageEntity(
                    date = dateString,
                    mobileDataUsed = mobileBytes,
                    wifiDataUsed = wifiBytes,
                    addOnDataUsed = addOnBytes
                )
            )
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    private val preferencesManager = com.example.data.PreferencesManager(application)
    private val networkStatsHelper = com.example.data.NetworkStatsHelper(application)

    fun resetTodayUsage() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (networkStatsHelper.isPermissionGranted()) {
                val rawMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                val rawWifi = networkStatsHelper.getWifiBytesSinceMidnight()
                preferencesManager.saveTodayOffsets(rawMobile, rawWifi, today)
            } else {
                preferencesManager.resetTodayOffsets()
            }
            repository.deleteTodayData(today)
            preferencesManager.resetAddOnUsage()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            if (networkStatsHelper.isPermissionGranted()) {
                val rawMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                val rawWifi = networkStatsHelper.getWifiBytesSinceMidnight()
                preferencesManager.saveTodayOffsets(rawMobile, rawWifi, today)
            } else {
                preferencesManager.resetTodayOffsets()
            }
            repository.clearAllHistory()
            preferencesManager.resetAddOnUsage()
        }
    }
}
