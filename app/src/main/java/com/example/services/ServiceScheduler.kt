package com.example.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
 *  - A 60-minute watchdog restarts the service if it was ever stopped — and when the OS
 *    refuses the restart it posts the "meter paused" notification instead of failing
 *    silently (the user would otherwise believe the meter is still counting).
 *  - A meter the user paused ([MeterControl]) is left alone by both paths.
 */
class SpeedKeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (SpeedMeterService.isServiceRunning.value) return Result.success()
        // Paused by the user: the watchdog has nothing to guard. No retry, no notice.
        if (!MeterControl.mayStart(applicationContext)) return Result.success()
        val accepted = SpeedMeterService.startService(applicationContext)
        if (accepted) delay(4_000L) // give the service time to come up (and to be refused)
        if (SpeedMeterService.isServiceRunning.value) return Result.success()
        // A worker runs in the background, and Android 12+ lets a background app start a
        // foreground service only after a user interaction (Android 15+ also refuses once
        // the dataSync budget is spent). Nothing WorkManager can do changes that — so the
        // user is told, the tap opens the app (always allowed), and the back-off retries.
        SpeedMeterService.notifyPaused(applicationContext)
        return Result.retry()
    }
}

object ServiceScheduler {

    private const val KEEP_ALIVE_WORK = "speed_meter_keep_alive"
    private const val RETRY_WORK = "speed_meter_timeout_retry"

    /** Default delay before the watchdog is allowed to try again after a timeout storm. */
    private const val RETRY_DELAY_MINUTES = 30L

    /** First back-off step when the retry itself fails (WorkManager doubles it each time). */
    private const val RETRY_BACKOFF_MINUTES = 10L

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

    /**
     * Called from `onTimeout` when the service hit the Android 15+ `dataSync` budget: the
     * service stops at once (it must) and the watchdog worker is scheduled for later.
     * WorkManager starts the FGS from a different (allowed) path, and if the budget has not
     * rolled over yet the worker's `retry()` backs off exponentially (10 → 20 → 40 min …)
     * instead of hammering the system.
     */
    fun scheduleRetry(context: Context, delayMinutes: Long = RETRY_DELAY_MINUTES) {
        try {
            val request = OneTimeWorkRequestBuilder<SpeedKeepAliveWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RETRY_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
