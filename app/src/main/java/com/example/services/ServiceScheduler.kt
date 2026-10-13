package com.example.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Keeps the meter alive through OS restraints:
 *  - Android 14/15+ may block background FGS starts (e.g. from BOOT_COMPLETED
 *    on API 35+), and Android 15+ caps the dataSync budget at ~6 h/day.
 *  - A 60-minute watchdog restarts the service if it was ever stopped.
 */
class SpeedKeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SpeedMeterService.isServiceRunning.value) {
            try {
                SpeedMeterService.startService(applicationContext)
            } catch (e: Exception) {
                return Result.retry()
            }
            delay(4_000L) // give the service time to come up
        }
        return if (SpeedMeterService.isServiceRunning.value) Result.success() else Result.retry()
    }
}

object ServiceScheduler {

    private const val KEEP_ALIVE_WORK = "speed_meter_keep_alive"

    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<SpeedKeepAliveWorker>(60, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                KEEP_ALIVE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
