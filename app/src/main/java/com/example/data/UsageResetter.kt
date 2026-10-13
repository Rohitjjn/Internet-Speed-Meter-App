package com.example.data

import android.content.Context
import com.example.util.DateFormatter

/**
 * Single source of truth for the "Reset today" action:
 * captures a system-counter baseline (when Usage access is granted) and
 * wipes today's row. The running service picks the new baseline up through
 * DataStore and re-seeds its integrated accumulators from the missing row.
 */
object UsageResetter {

    suspend fun resetToday(context: Context) {
        val today = DateFormatter.day()
        val helper = NetworkStatsHelper(context)
        if (helper.isPermissionGranted()) {
            val m = helper.getMobileSinceMidnight()
            val w = helper.getWifiOrEthernetSinceMidnight()
            val prefs = PreferencesManager(context)
            prefs.saveResetOffsets(ResetOffsets(today, m.rx, m.tx, w.rx, w.tx))
        }
        val repository = DataUsageRepository(AppDatabase.getDatabase(context).dataUsageDao())
        repository.deleteByDate(today)
    }

    suspend fun clearAll(context: Context) {
        // Reset today as well so the day restarts cleanly.
        resetToday(context)
        val repository = DataUsageRepository(AppDatabase.getDatabase(context).dataUsageDao())
        repository.clearAll()
    }
}
