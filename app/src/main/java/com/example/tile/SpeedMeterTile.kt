package com.example.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DataUsageRepository
import com.example.services.SpeedMeterService
import com.example.util.BytesFormatter
import com.example.util.DateFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile — one tap from the shade into the app (where the speed test lives).
 *
 * Honest by construction: the tile's state mirrors whether the meter is actually running,
 * and the subtitle (API 29+) is **today's persisted total** — a row the service wrote from
 * OS counters — or a dash while there is none. It never shows a live speed, because a
 * tile cannot follow one without keeping the process awake.
 */
class SpeedMeterTile : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val running = SpeedMeterService.isServiceRunning.value
        renderTile(running = running, todayTotal = null)

        val listeningScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope?.cancel()
        scope = listeningScope
        listeningScope.launch {
            val total = try {
                DataUsageRepository(AppDatabase.getDatabase(applicationContext).dataUsageDao())
                    .getByDate(DateFormatter.day())
                    ?.total
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (scope === listeningScope) renderTile(running = running, todayTotal = total)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderTile(running: Boolean, todayTotal: Long?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val subtitle = when {
            todayTotal != null -> getString(R.string.tile_subtitle_today, BytesFormatter.format(todayTotal))
            running -> getString(R.string.tile_subtitle_running)
            else -> getString(R.string.tile_subtitle_paused)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.contentDescription = "${tile.label} · $subtitle"
        tile.updateTile()
    }
}
