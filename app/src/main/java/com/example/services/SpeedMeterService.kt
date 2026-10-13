package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import com.example.data.NetworkStatsHelper
import com.example.data.PreferencesManager
import com.example.data.ResetOffsets
import com.example.data.TransportBytes
import com.example.util.BytesFormatter
import com.example.util.SpeedFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The real-time engine of the app.
 *
 * Architecture (this is what fixes "speed not updating like other meter apps"):
 *
 *  1. [startSpeedTicker] — a lean 1 Hz loop on Dispatchers.Default. It ONLY reads
 *     counters and publishes speed. No Room, no NetworkStatsManager, no bitmap
 *     work in the hot path (icon is cached) — so the loop keeps a steady
 *     1-second cadence even on slow devices, exactly like dedicated speed apps.
 *
 *  2. [startUsageAccounting] — a slow (15 s) loop that does the expensive work:
 *     NetworkStatsManager queries and Room upserts for the daily usage cards.
 *
 *  3. Counter sources: TrafficStats (primary) + /proc/net/dev (fallback) with a
 *     health check that switches automatically when a source goes stale
 *     (happens on some OEM ROMs where TrafficStats totals are restricted).
 */
class SpeedMeterService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var tickerJob: Job? = null
    private var accountingJob: Job? = null

    private lateinit var repository: DataUsageRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var networkStatsHelper: NetworkStatsHelper

    // ----- daily accounting state -----
    private var hasUsageStatsPermission = false
    private var lastAccountedDay = ""
    private var resetOffsets = ResetOffsets()
    // Integrated fallback accumulators (used when "Usage access" is not granted):
    // every 1s tick delta is attributed to the active transport.
    private var integratedMobileRx = 0L
    private var integratedMobileTx = 0L
    private var integratedWifiRx = 0L
    private var integratedWifiTx = 0L

    // ----- status-bar speed icon cache -----
    private var cachedSpeedIcon: IconCompat? = null
    private var lastSpeedText = ""
    private var lastSpeedUnit = ""

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speed_meter_channel"

        const val NETWORK_WIFI = "WIFI"
        const val NETWORK_MOBILE = "MOBILE"
        const val NETWORK_NONE = "NONE"

        private const val TICK_MS = 1000L
        private const val ACCOUNTING_PERIOD_MS = 15_000L
        /** Windows longer than this mean the service was suspended — don't publish a misleading average. */
        private const val MAX_VALID_WINDOW_MS = 4_000L
        /** Ticks without any counter movement before we suspect a stale source. */
        private const val STAGNANT_THRESHOLD = 5
        /** Sparkline depth: last N seconds of speed samples. */
        private const val HISTORY_SIZE = 60

        private val _downloadSpeedFlow = MutableStateFlow(0L) // bytes/sec
        val downloadSpeedFlow: StateFlow<Long> = _downloadSpeedFlow.asStateFlow()

        private val _uploadSpeedFlow = MutableStateFlow(0L) // bytes/sec
        val uploadSpeedFlow: StateFlow<Long> = _uploadSpeedFlow.asStateFlow()

        private val _dlHistoryFlow = MutableStateFlow<List<Long>>(emptyList())
        val dlHistoryFlow: StateFlow<List<Long>> = _dlHistoryFlow.asStateFlow()

        private val _ulHistoryFlow = MutableStateFlow<List<Long>>(emptyList())
        val ulHistoryFlow: StateFlow<List<Long>> = _ulHistoryFlow.asStateFlow()

        private val _peakDownloadFlow = MutableStateFlow(0L) // bytes/sec, this session
        val peakDownloadFlow: StateFlow<Long> = _peakDownloadFlow.asStateFlow()

        private val _peakUploadFlow = MutableStateFlow(0L)
        val peakUploadFlow: StateFlow<Long> = _peakUploadFlow.asStateFlow()

        private val _networkTypeFlow = MutableStateFlow(NETWORK_NONE)
        val networkTypeFlow: StateFlow<String> = _networkTypeFlow.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _todayUsageFlow = MutableStateFlow(DataUsageEntity("1970-01-01"))
        val todayUsageFlow: StateFlow<DataUsageEntity> = _todayUsageFlow.asStateFlow()

        private val _permissionGrantedFlow = MutableStateFlow(false)
        val permissionGrantedFlow: StateFlow<Boolean> = _permissionGrantedFlow.asStateFlow()

        fun startService(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, SpeedMeterService::class.java))
            } catch (e: Exception) {
                // Background start blocked by the OS (Android 14/15+); the WorkManager
                // watchdog or the next app open will (re)start the service.
                e.printStackTrace()
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, SpeedMeterService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun currentDay(): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
    }

    override fun onCreate() {
        super.onCreate()
        repository = DataUsageRepository(AppDatabase.getDatabase(this).dataUsageDao())
        preferencesManager = PreferencesManager(this)
        networkStatsHelper = NetworkStatsHelper(this)
        createNotificationChannel()
        // Keep the "reset today" baseline fresh in memory (DataStore is async).
        serviceScope.launch {
            preferencesManager.resetOffsetsFlow.collect { resetOffsets = it }
        }
        startSpeedTicker()
        startUsageAccounting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isServiceRunning.value = true
        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ (API 35): dataSync FGS get a 6-hour-per-day budget. When the
     * budget is exhausted the system calls this. We restart the service to get a
     * fresh budget; if the OS refuses the background restart we stop cleanly.
     */
    override fun onTimeout(startId: Int, timeoutType: Int) {
        try {
            ContextCompat.startForegroundService(this, Intent(this, SpeedMeterService::class.java))
        } catch (e: Exception) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        accountingJob?.cancel()
        serviceJob.cancel()
        _isServiceRunning.value = false
        _downloadSpeedFlow.value = 0L
        _uploadSpeedFlow.value = 0L
        cachedSpeedIcon = null
        lastSpeedText = ""
        lastSpeedUnit = ""
    }

    // ================================ SPEED TICKER ================================

    private fun startSpeedTicker() {
        tickerJob = serviceScope.launch(Dispatchers.Default) {
            var activeSource = CounterSource.TRAFFIC_STATS
            var lastSample = TrafficCounterReader.read(activeSource)
            // Cold-start source pick: if TrafficStats is dead (0/0 or stale on some ROMs)
            // but the kernel counters have data, start with /proc/net/dev.
            if (lastSample.isZero) {
                val kernelSample = TrafficCounterReader.read(CounterSource.PROC_NET_DEV)
                if (!kernelSample.isZero) {
                    activeSource = CounterSource.PROC_NET_DEV
                    lastSample = kernelSample
                }
            }
            var stagnantTicks = 0
            val dlHistory = ArrayDeque<Long>()
            val ulHistory = ArrayDeque<Long>()
            var nextTickAt = SystemClock.elapsedRealtime() + TICK_MS

            while (true) {
                // Deadline scheduling: keeps a steady 1 Hz cadence without drift.
                val now = SystemClock.elapsedRealtime()
                delay((nextTickAt - now).coerceIn(20L, 5_000L))
                val readAt = SystemClock.elapsedRealtime()
                nextTickAt += TICK_MS
                if (readAt > nextTickAt + TICK_MS) nextTickAt = readAt + TICK_MS // re-anchor after suspension

                val sample = TrafficCounterReader.read(activeSource)

                // ---- Source health check: switch when the active source is stale ----
                if (!sample.movedFrom(lastSample)) {
                    stagnantTicks++
                    if (stagnantTicks >= STAGNANT_THRESHOLD) {
                        val other = if (activeSource == CounterSource.TRAFFIC_STATS) {
                            CounterSource.PROC_NET_DEV
                        } else {
                            CounterSource.TRAFFIC_STATS
                        }
                        val otherSample = TrafficCounterReader.read(other)
                        if (otherSample.movedFrom(lastSample) || (!lastSample.total > 0L && !otherSample.isZero)) {
                            activeSource = other
                            lastSample = otherSample
                            stagnantTicks = 0
                            continue
                        }
                    }
                } else {
                    stagnantTicks = 0
                }

                val windowMs = sample.timestampMs - lastSample.timestampMs
                if (windowMs in 1..MAX_VALID_WINDOW_MS) {
                    val dlBytes = (sample.rx - lastSample.rx).coerceAtLeast(0L)
                    val ulBytes = (sample.tx - lastSample.tx).coerceAtLeast(0L)
                    val windowSec = windowMs / 1000.0
                    val dlSpeed = (dlBytes / windowSec).toLong()
                    val ulSpeed = (ulBytes / windowSec).toLong()

                    // Integrated daily accounting fallback (no "Usage access" permission):
                    // attribute real per-second bytes to the active transport.
                    if (!hasUsageStatsPermission) {
                        when (getActiveNetworkType()) {
                            NETWORK_WIFI -> {
                                integratedWifiRx += dlBytes
                                integratedWifiTx += ulBytes
                            }
                            NETWORK_MOBILE -> {
                                integratedMobileRx += dlBytes
                                integratedMobileTx += ulBytes
                            }
                            else -> Unit
                        }
                    }

                    publishSpeed(dlSpeed, ulSpeed, dlHistory, ulHistory)
                } else {
                    // Service was suspended (Doze / killed); an averaged speed across the
                    // gap would be a fake spike or dip — report 0 like real meters do.
                    publishSpeed(0L, 0L, dlHistory, ulHistory)
                }
                lastSample = sample
            }
        }
    }

    private fun publishSpeed(
        dlSpeed: Long,
        ulSpeed: Long,
        dlHistory: ArrayDeque<Long>,
        ulHistory: ArrayDeque<Long>
    ) {
        _downloadSpeedFlow.value = dlSpeed
        _uploadSpeedFlow.value = ulSpeed

        dlHistory.addLast(dlSpeed)
        ulHistory.addLast(ulSpeed)
        while (dlHistory.size > HISTORY_SIZE) dlHistory.removeFirst()
        while (ulHistory.size > HISTORY_SIZE) ulHistory.removeFirst()
        _dlHistoryFlow.value = dlHistory.toList()
        _ulHistoryFlow.value = ulHistory.toList()

        if (dlSpeed > _peakDownloadFlow.value) _peakDownloadFlow.value = dlSpeed
        if (ulSpeed > _peakUploadFlow.value) _peakUploadFlow.value = ulSpeed

        updateNotification(dlSpeed, ulSpeed)
    }

    private fun getActiveNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return NETWORK_NONE
                val capabilities = cm.getNetworkCapabilities(network) ?: return NETWORK_NONE
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NETWORK_WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NETWORK_MOBILE
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NETWORK_WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NETWORK_WIFI
                    else -> NETWORK_NONE
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return NETWORK_NONE
                @Suppress("DEPRECATION")
                when (info.type) {
                    ConnectivityManager.TYPE_WIFI -> NETWORK_WIFI
                    ConnectivityManager.TYPE_MOBILE -> NETWORK_MOBILE
                    ConnectivityManager.TYPE_ETHERNET -> NETWORK_WIFI
                    else -> NETWORK_NONE
                }
            }
        } catch (e: Exception) {
            NETWORK_NONE
        }
    }

    // ============================ USAGE ACCOUNTING (SLOW) ============================

    private fun startUsageAccounting() {
        accountingJob = serviceScope.launch {
            // Make sure the reset baseline is loaded before the first accounting pass.
            try {
                resetOffsets = preferencesManager.resetOffsetsFlow.first()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Restore integrated accumulators from the DB after a service/process restart.
            networkStatsHelper.isPermissionGranted().let { granted ->
                hasUsageStatsPermission = granted
                _permissionGrantedFlow.value = granted
            }
            if (!hasUsageStatsPermission) {
                val row = repository.getByDate(currentDay())
                if (row != null) {
                    integratedMobileRx = row.mobileRx
                    integratedMobileTx = row.mobileTx
                    integratedWifiRx = row.wifiRx
                    integratedWifiTx = row.wifiTx
                }
            }
            _todayUsageFlow.value = buildTodayEntity()
            while (true) {
                delay(ACCOUNTING_PERIOD_MS)
                accountUsage()
            }
        }
    }

    private suspend fun accountUsage() {
        val today = currentDay()

        // Midnight rollover: new day starts fresh for the integrated path.
        if (lastAccountedDay.isNotEmpty() && lastAccountedDay != today && !hasUsageStatsPermission) {
            integratedMobileRx = 0L
            integratedMobileTx = 0L
            integratedWifiRx = 0L
            integratedWifiTx = 0L
        }
        lastAccountedDay = today

        // Permission state can change at any time (user grants/revokes in Settings).
        val granted = networkStatsHelper.isPermissionGranted()
        if (granted && !hasUsageStatsPermission) {
            // System counters become authoritative — no seeding needed.
            hasUsageStatsPermission = true
        } else if (!granted && hasUsageStatsPermission) {
            // Falling back to integrated accounting: continue from the current row.
            hasUsageStatsPermission = false
            val row = repository.getByDate(today)
            if (row != null) {
                integratedMobileRx = row.mobileRx
                integratedMobileTx = row.mobileTx
                integratedWifiRx = row.wifiRx
                integratedWifiTx = row.wifiTx
            }
        }
        _permissionGrantedFlow.value = hasUsageStatsPermission

        val entity = if (hasUsageStatsPermission) {
            // REAL system-wide counters per transport, minus the "reset today" baseline.
            val mobile = networkStatsHelper.getMobileSinceMidnight()
            val wifi = networkStatsHelper.getWifiOrEthernetSinceMidnight()
            val m = if (resetOffsets.appliesTo(today)) {
                mobile - TransportBytes(resetOffsets.mobileRx, resetOffsets.mobileTx)
            } else mobile
            val w = if (resetOffsets.appliesTo(today)) {
                wifi - TransportBytes(resetOffsets.wifiRx, resetOffsets.wifiTx)
            } else wifi
            DataUsageEntity(today, m.rx, m.tx, w.rx, w.tx)
        } else {
            // Detect an external "reset today" (row deleted by the UI).
            if ((integratedMobileRx + integratedMobileTx + integratedWifiRx + integratedWifiTx) > 0L) {
                val existing = repository.getByDate(today)
                if (existing == null) {
                    integratedMobileRx = 0L
                    integratedMobileTx = 0L
                    integratedWifiRx = 0L
                    integratedWifiTx = 0L
                }
            }
            buildTodayEntity()
        }

        try {
            repository.upsertData(entity)
            _todayUsageFlow.value = entity
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildTodayEntity(): DataUsageEntity {
        val today = currentDay()
        return DataUsageEntity(today, integratedMobileRx, integratedMobileTx, integratedWifiRx, integratedWifiTx)
    }

    /**
     * Resets today's counters. Persists a baseline (system-counter path) and
     * wipes the row; the integrated path re-seeds itself from the missing row.
     */
    suspend fun resetToday() {
        val today = currentDay()
        if (networkStatsHelper.isPermissionGranted()) {
            val m = networkStatsHelper.getMobileSinceMidnight()
            val w = networkStatsHelper.getWifiOrEthernetSinceMidnight()
            resetOffsets = ResetOffsets(today, m.rx, m.tx, w.rx, w.tx)
            preferencesManager.saveResetOffsets(resetOffsets)
        }
        integratedMobileRx = 0L
        integratedMobileTx = 0L
        integratedWifiRx = 0L
        integratedWifiTx = 0L
        repository.deleteByDate(today)
        _todayUsageFlow.value = DataUsageEntity(today)
    }

    // ================================ NOTIFICATION ================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live speed ticker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time internet speed in the status bar."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildSpeedNotification(0L, 0L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stopSelf()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun updateNotification(dlBytes: Long, ulBytes: Long) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildSpeedNotification(dlBytes, ulBytes))
        } catch (e: Exception) {
            // Notifications disabled — ticker keeps running, UI flows are unaffected.
        }
    }

    private fun buildSpeedNotification(dlBytes: Long, ulBytes: Long): Notification {
        val networkLabel = when (_networkTypeFlow.value) {
            NETWORK_WIFI -> "Wi-Fi"
            NETWORK_MOBILE -> "Mobile"
            else -> "Offline"
        }
        val todayTotal = _todayUsageFlow.value.total
        val title = "↓ ${SpeedFormatter.format(dlBytes)}  ↑ ${SpeedFormatter.format(ulBytes)}"
        val text = "$networkLabel · Today ${BytesFormatter.format(todayTotal)}"

        return try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppIntent())

            val speedIcon = createSpeedIcon(dlBytes)
            if (speedIcon != null) {
                try {
                    builder.setSmallIcon(speedIcon)
                } catch (e: Exception) {
                    builder.setSmallIcon(R.drawable.ic_network_icon)
                }
            } else {
                builder.setSmallIcon(R.drawable.ic_network_icon)
            }
            builder.build()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSmallIcon(R.drawable.ic_network_icon)
                    .build()
            } catch (ex: Exception) {
                NotificationCompat.Builder(this, CHANNEL_ID).build()
            }
        }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Custom status-bar icon: the live download speed as text (the signature look
     * of dedicated speed-meter apps). Bitmap is only redrawn when the string
     * changes, so the 1 Hz loop stays cheap.
     */
    private fun createSpeedIcon(downloadSpeed: Long): IconCompat? {
        return try {
            val (text, unit) = SpeedFormatter.split(downloadSpeed)
            if (text == lastSpeedText && unit == lastSpeedUnit && cachedSpeedIcon != null) {
                return cachedSpeedIcon
            }

            val width = 144
            val height = 144
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }

            paint.textSize = when (text.length) {
                1 -> 102f
                2 -> 87f
                3 -> 66f
                else -> 54f
            }
            canvas.drawText(text, width / 2f, 75f, paint)
            paint.textSize = 51f
            canvas.drawText(unit, width / 2f, 130f, paint)

            val icon = IconCompat.createWithBitmap(bitmap)
            cachedSpeedIcon = icon
            lastSpeedText = text
            lastSpeedUnit = unit
            icon
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
