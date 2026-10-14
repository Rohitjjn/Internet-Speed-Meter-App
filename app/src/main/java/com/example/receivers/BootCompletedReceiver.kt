package com.example.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.services.MeterControl
import com.example.services.ServiceScheduler
import com.example.services.SpeedMeterService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // The "paused by you" flag lives in DataStore (async), so the receiver stays
            // alive with goAsync() while it is read. A meter the user switched off must not
            // come back just because the phone rebooted.
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    if (MeterControl.mayStart(context)) {
                        // On API 35+ the OS blocks dataSync FGS starts from BOOT_COMPLETED.
                        // The service posts the "meter paused" notification when its
                        // promotion is refused, and the watchdog keeps trying.
                        if (!SpeedMeterService.startService(context)) SpeedMeterService.notifyPaused(context)
                    }
                    ServiceScheduler.schedule(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
