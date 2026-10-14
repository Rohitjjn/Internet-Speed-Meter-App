package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import com.example.data.PreferencesManager
import com.example.data.UsageResetter
import com.example.util.LimitAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.example.util.DateFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DataUsageRepository
    private val applicationContext = application

    val historyList: StateFlow<List<DataUsageEntity>>
    val monthlyTotalBytes: StateFlow<Long>
    /** Same month one month earlier — powers the "vs last month" recap. */
    val previousMonthTotalBytes: StateFlow<Long>
    /** Short label of the current month, e.g. "Oct 2026". */
    val monthLabel: StateFlow<String>
    /** Month-to-date mobile bytes (what the optional monthly limit is compared against). */
    val monthlyMobileBytes: StateFlow<Long>
    /** The optional monthly mobile limit in bytes; 0 = not set. */
    val monthlyLimitBytes: StateFlow<Long>
    /** "40 GB"-style label of the monthly limit; empty when not set. */
    val monthlyLimitLabel: StateFlow<String>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DataUsageRepository(database.dataUsageDao())

        val calendar = Calendar.getInstance()
        // Row keys are Locale.US (see DateFormatter.day); the LIKE prefix must match them.
        val currentMonthPrefix = DateFormatter.monthPrefix(calendar.time) + "-%"
        calendar.add(Calendar.MONTH, -1)
        val previousMonthPrefix = DateFormatter.monthPrefix(calendar.time) + "-%"
        val label = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date())

        historyList = repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        monthlyTotalBytes = repository.getThisMonthTotal(currentMonthPrefix)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0L
            )

        previousMonthTotalBytes = repository.getThisMonthTotal(previousMonthPrefix)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0L
            )

        monthLabel = MutableStateFlow(label).asStateFlow()

        monthlyMobileBytes = repository.getThisMonthMobileTotal(currentMonthPrefix)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

        val preferences = PreferencesManager(application)
        monthlyLimitBytes = combine(preferences.monthlyLimitFlow, preferences.monthlyLimitUnitFlow) { limit, unit ->
            LimitAlerts.limitBytes(limit, unit)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

        monthlyLimitLabel = combine(preferences.monthlyLimitFlow, preferences.monthlyLimitUnitFlow) { limit, unit ->
            if (limit > 0.0) {
                val value = if (limit % 1.0 == 0.0) limit.toInt().toString() else limit.toString()
                "$value $unit"
            } else {
                ""
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
        // Note: no dummy/sample data is seeded — history always shows REAL usage.
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
