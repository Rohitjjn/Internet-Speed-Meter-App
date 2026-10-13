package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import com.example.data.UsageResetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DataUsageRepository
    private val applicationContext = application

    val historyList: StateFlow<List<DataUsageEntity>>
    val monthlyTotalBytes: StateFlow<Long>
    /** Last calendar month's total — only used for the "vs last month" delta. */
    val lastMonthTotalBytes: StateFlow<Long>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = DataUsageRepository(database.dataUsageDao())

        val dateFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val currentMonthPrefix = dateFormat.format(Date()) + "-%"
        val previousMonthPrefix = dateFormat.format(
            Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time
        ) + "-%"

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

        lastMonthTotalBytes = repository.getThisMonthTotal(previousMonthPrefix)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0L
            )
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
