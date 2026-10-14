package com.example.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.services.SpeedMeterService
import com.example.util.DateFormatter
import java.util.concurrent.TimeUnit

/**
 * Re-renders [TodayUsageWidget] from the persisted daily row. Runs every 30 minutes while
 * at least one widget is placed, plus once right after a widget is added/resized.
 *
 * When the meter service is running its in-memory row is fresher than the database
 * (accounting writes every 15 s), so that one is preferred.
 */
class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!TodayUsageWidget.hasInstances(context)) return Result.success()
        val today = DateFormatter.day()
        val entity = try {
            val live = SpeedMeterService.todayUsageFlow.value
            if (SpeedMeterService.isServiceRunning.value && live.date == today) {
                live
            } else {
                AppDatabase.getDatabase(context).dataUsageDao().getByDate(today) ?: DataUsageEntity(today)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
        TodayUsageWidget.render(context, entity)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK = "today_widget_refresh"
        private const val ONE_SHOT_WORK = "today_widget_refresh_now"
        private const val PERIOD_MINUTES = 30L

        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun refreshNow(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_SHOT_WORK,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
