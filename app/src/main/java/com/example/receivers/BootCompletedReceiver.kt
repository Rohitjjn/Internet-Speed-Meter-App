package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.services.ServiceScheduler
import com.example.services.SpeedMeterService

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // On API 35+ the OS blocks dataSync FGS starts from BOOT_COMPLETED. The service
            // itself posts the "meter paused" notification when its promotion is refused,
            // and the WorkManager watchdog keeps trying in the background.
            if (!SpeedMeterService.startService(context)) SpeedMeterService.notifyPaused(context)
            ServiceScheduler.schedule(context)
        }
    }
}
