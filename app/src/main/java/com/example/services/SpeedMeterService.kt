package com.example.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DataUsageEntity
import com.example.data.DataUsageRepository
import com.example.data.NetworkStatsHelper
import com.example.data.PreferencesManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpeedMeterService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var tickerJob: Job? = null
    private var useBitmapIcon = true // Enable status-bar custom bitmap speed indicator by default

    private lateinit var database: AppDatabase
    private lateinit var repository: DataUsageRepository
    private lateinit var preferencesManager: PreferencesManager

    private var cachedSpeedIcon: IconCompat? = null
    private var lastSpeedText = ""
    private var lastSpeedUnit = ""

    // High-performance reactive cache to bypass expensive Disk I/O inside the 1-second busy loop
    private var cachedIsAddOnActive = false
    private var cachedTodayOffsetDate = ""
    private var cachedTodayMobileOffset = 0L
    private var cachedTodayWifiOffset = 0L
    private var cachedAddOnBaseline = 0L
    private var cachedAddOnSavedToday = 0L
    private var cachedAddOnSavedTodayDate = ""
    private var cachedAddOnActivationDate = ""
    private var cachedMobileLimit = 2.0
    private var cachedMobileLimitUnit = "GB"
    private var cachedAddonLimit = 500.0
    private var cachedAddonLimitUnit = "MB"

    private var cachedTodayEntity: DataUsageEntity? = null
    private var trackingDate = ""
    private var dbCollectionJob: Job? = null

    // Persistent alert flags matching daily boundaries
    private var alertedMobile50 = false
    private var alertedMobile90 = false
    private var alertedMobile100 = false

    private var alertedAddon50 = false
    private var alertedAddon90 = false
    private var alertedAddon100 = false

    private var lastTrackingDate = ""

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speed_meter_channel_id"
        private const val ALERTS_CHANNEL_ID = "bandwidth_alerts_channel_id"

        private val _downloadSpeedFlow = MutableStateFlow(0L) // bytes/sec
        val downloadSpeedFlow: StateFlow<Long> = _downloadSpeedFlow.asStateFlow()

        private val _uploadSpeedFlow = MutableStateFlow(0L) // bytes/sec
        val uploadSpeedFlow: StateFlow<Long> = _uploadSpeedFlow.asStateFlow()

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun startService(context: Context) {
            try {
                val intent = Intent(context, SpeedMeterService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, SpeedMeterService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        repository = DataUsageRepository(database.dataUsageDao())
        preferencesManager = PreferencesManager(this)
        createNotificationChannels()
        startCachingPreferenceFlows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isServiceRunning.value = true
        startForegroundWithNotification()
        startSpeedTicker()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        dbCollectionJob?.cancel()
        serviceJob.cancel()
        _isServiceRunning.value = false
        _downloadSpeedFlow.value = 0L
        _uploadSpeedFlow.value = 0L
        cachedSpeedIcon = null
        lastSpeedText = ""
        lastSpeedUnit = ""
    }

    private fun startCachingPreferenceFlows() {
        serviceScope.launch {
            preferencesManager.isAddOnActiveFlow.collect { cachedIsAddOnActive = it }
        }
        serviceScope.launch {
            preferencesManager.todayOffsetDateFlow.collect { cachedTodayOffsetDate = it }
        }
        serviceScope.launch {
            preferencesManager.todayMobileOffsetFlow.collect { cachedTodayMobileOffset = it }
        }
        serviceScope.launch {
            preferencesManager.todayWifiOffsetFlow.collect { cachedTodayWifiOffset = it }
        }
        serviceScope.launch {
            preferencesManager.addOnBaselineFlow.collect { cachedAddOnBaseline = it }
        }
        serviceScope.launch {
            preferencesManager.addOnSavedTodayFlow.collect { cachedAddOnSavedToday = it }
        }
        serviceScope.launch {
            preferencesManager.addOnSavedTodayDateFlow.collect { cachedAddOnSavedTodayDate = it }
        }
        serviceScope.launch {
            preferencesManager.addOnActivationDateFlow.collect { cachedAddOnActivationDate = it }
        }
        serviceScope.launch {
            preferencesManager.mobileLimitFlow.collect { cachedMobileLimit = it }
        }
        serviceScope.launch {
            preferencesManager.mobileLimitUnitFlow.collect { cachedMobileLimitUnit = it }
        }
        serviceScope.launch {
            preferencesManager.addonLimitFlow.collect { cachedAddonLimit = it }
        }
        serviceScope.launch {
            preferencesManager.addonLimitUnitFlow.collect { cachedAddonLimitUnit = it }
        }
    }

    private fun startDbTrackingForDate(dateStr: String) {
        if (trackingDate == dateStr) return
        trackingDate = dateStr
        dbCollectionJob?.cancel()
        dbCollectionJob = serviceScope.launch {
            repository.getDataByDate(dateStr).collect { entity ->
                cachedTodayEntity = entity
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildSpeedNotification(0L, 0L, 0L, 0L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
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

    private fun getActiveNetworkType(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "WIFI"
                val capabilities = cm.getNetworkCapabilities(network) ?: return "WIFI"
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "WIFI"
                    else -> "WIFI"
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo ?: return "WIFI"
                @Suppress("DEPRECATION")
                when (info.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                    ConnectivityManager.TYPE_ETHERNET -> "WIFI"
                    else -> "WIFI"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "WIFI"
        }
    }

    private fun startSpeedTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            var lastRxBytes = TrafficStats.getTotalRxBytes()
            var lastTxBytes = TrafficStats.getTotalTxBytes()
            var lastTime = android.os.SystemClock.elapsedRealtime()
            var lastProcessedDate = ""

            while (true) {
                delay(1000)

                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()
                val currentTime = android.os.SystemClock.elapsedRealtime()

                val timeDeltaMs = currentTime - lastTime
                val timeDeltaSec = if (timeDeltaMs > 0) timeDeltaMs / 1000.0 else 1.0

                // Calculate deltas, handling device reboot reset cases gracefully
                val dlDelta = if (lastRxBytes in 1..currentRx) currentRx - lastRxBytes else 0L
                val ulDelta = if (lastTxBytes in 1..currentTx) currentTx - lastTxBytes else 0L

                lastRxBytes = currentRx
                lastTxBytes = currentTx
                lastTime = currentTime

                // Scale deltas up to represent bytes per second in real-time
                val dlSpeed = if (timeDeltaSec > 0.1 && !timeDeltaSec.isNaN() && !timeDeltaSec.isInfinite()) {
                    (dlDelta / timeDeltaSec).toLong()
                } else {
                    dlDelta
                }
                val ulSpeed = if (timeDeltaSec > 0.1 && !timeDeltaSec.isNaN() && !timeDeltaSec.isInfinite()) {
                    (ulDelta / timeDeltaSec).toLong()
                } else {
                    ulDelta
                }

                _downloadSpeedFlow.value = dlSpeed
                _uploadSpeedFlow.value = ulSpeed

                val todayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                if (lastProcessedDate.isNotEmpty() && lastProcessedDate != todayString) {
                    // Midnight transition! Reset local parameters to start clean
                    cachedTodayEntity = null
                    serviceScope.launch {
                        preferencesManager.resetTodayOffsets()
                        preferencesManager.saveAddOnSavedToday(0L, todayString)
                    }
                }
                lastProcessedDate = todayString

                startDbTrackingForDate(todayString)

                val isAddOn = cachedIsAddOnActive
                val networkStatsHelper = NetworkStatsHelper(this@SpeedMeterService)

                // Ensure a row for today exists in Room database without overwriting existing data (Using IGNORE schema)
                try {
                    val initialEntity = DataUsageEntity(
                        date = todayString,
                        mobileDataUsed = 0L,
                        wifiDataUsed = 0L,
                        addOnDataUsed = 0L
                    )
                    repository.insertDataIgnore(initialEntity)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Pre-fetch today's record synchronously to bypass async Flow collection lag on cold start
                var currentTodayEntity = cachedTodayEntity
                if (currentTodayEntity == null) {
                    try {
                        currentTodayEntity = repository.getDataByDate(todayString).first()
                        if (currentTodayEntity != null) {
                            cachedTodayEntity = currentTodayEntity
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                var mobileUsed = 0L
                var wifiUsed = 0L

                try {
                    if (networkStatsHelper.isPermissionGranted()) {
                        val rawMobile = networkStatsHelper.getMobileBytesSinceMidnight()
                        val rawWifi = networkStatsHelper.getWifiBytesSinceMidnight() + networkStatsHelper.getEthernetBytesSinceMidnight()

                        // TODAY Reset Offset Subtraction
                        val offsetDate = cachedTodayOffsetDate
                        val mobileOffset = if (offsetDate == todayString) cachedTodayMobileOffset else 0L
                        val wifiOffset = if (offsetDate == todayString) cachedTodayWifiOffset else 0L

                        val rawMobileAdjusted = maxOf(0L, rawMobile - mobileOffset)
                        val rawWifiAdjusted = maxOf(0L, rawWifi - wifiOffset)

                        var baseline = cachedAddOnBaseline
                        if (isAddOn && baseline <= 0L) {
                            preferencesManager.saveAddOnBaseline(rawMobile)
                            baseline = rawMobile
                        }

                        val savedTodayDate = cachedAddOnSavedTodayDate
                        val savedTodayBytes = if (savedTodayDate == todayString) {
                            cachedAddOnSavedToday
                        } else {
                            0L
                        }

                        val activationDate = cachedAddOnActivationDate
                        val mobileBytes: Long
                        val addOnBytes: Long

                        if (isAddOn) {
                            if (todayString == activationDate || activationDate.isEmpty()) {
                                val baselineAdjusted = maxOf(0L, baseline - mobileOffset)
                                val currentSessionAddOn = maxOf(0L, rawMobileAdjusted - baselineAdjusted)
                                addOnBytes = savedTodayBytes + currentSessionAddOn
                                mobileBytes = maxOf(0L, rawMobileAdjusted - addOnBytes)
                            } else {
                                addOnBytes = savedTodayBytes + rawMobileAdjusted
                                mobileBytes = 0L
                            }
                        } else {
                            addOnBytes = savedTodayBytes
                            mobileBytes = maxOf(0L, rawMobileAdjusted - addOnBytes)
                        }

                        mobileUsed = mobileBytes
                        wifiUsed = rawWifiAdjusted

                        // Save or update today's entity in Room (Primary Key 'date' automatically merges or updates it)
                        val updated = DataUsageEntity(
                            date = todayString,
                            mobileDataUsed = mobileBytes,
                            wifiDataUsed = rawWifiAdjusted,
                            addOnDataUsed = addOnBytes
                        )
                        repository.insertData(updated)
                    } else {
                        // --- FALLBACK INTERACTIVE TRAFFIC GENERATION ENGINE ---
                        val existing = currentTodayEntity ?: (cachedTodayEntity?.takeIf { it.date == todayString })

                        var mobileBytes = existing?.mobileDataUsed ?: 0L
                        var wifiBytes = existing?.wifiDataUsed ?: 0L
                        var addOnBytes = existing?.addOnDataUsed ?: 0L

                        val totalDelta = dlDelta + ulDelta
                        if (totalDelta > 0) {
                            val networkType = getActiveNetworkType()
                            if (networkType == "WIFI") {
                                wifiBytes += totalDelta
                            } else if (networkType == "MOBILE") {
                                if (isAddOn) {
                                    addOnBytes += totalDelta
                                } else {
                                    mobileBytes += totalDelta
                                }
                            }
                        }

                        mobileUsed = mobileBytes
                        wifiUsed = wifiBytes

                        val updated = DataUsageEntity(
                            date = todayString,
                            mobileDataUsed = mobileBytes,
                            wifiDataUsed = wifiBytes,
                            addOnDataUsed = addOnBytes
                        )
                        repository.insertData(updated)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // If DB insert fails, fallback to existing or 0L but don't crash
                    val existing = currentTodayEntity ?: cachedTodayEntity
                    mobileUsed = existing?.mobileDataUsed ?: 0L
                    wifiUsed = existing?.wifiDataUsed ?: 0L
                }

                // Live-update notification
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val updatedNotification = buildSpeedNotification(dlSpeed, ulSpeed, mobileUsed, wifiUsed)
                try {
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Note: Background notifications alerts are disabled per user request in favor of real-time on-screen popups
            }
        }
    }

    private suspend fun checkDataAlerts() {
        val todayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (lastTrackingDate != todayString) {
            lastTrackingDate = todayString
            alertedMobile50 = false
            alertedMobile90 = false
            alertedMobile100 = false
            alertedAddon50 = false
            alertedAddon90 = false
            alertedAddon100 = false
        }

        val mobileLimit = cachedMobileLimit
        val mobileLimitUnit = cachedMobileLimitUnit
        val addonLimit = cachedAddonLimit
        val addonLimitUnit = cachedAddonLimitUnit

        val todayEntity = cachedTodayEntity
        if (todayEntity != null) {
            val mobileUsed = todayEntity.mobileDataUsed
            val addonUsed = todayEntity.addOnDataUsed

            val mobileLimitBytes = convertToBytes(mobileLimit, mobileLimitUnit)
            val addonLimitBytes = convertToBytes(addonLimit, addonLimitUnit)

            // Mobile limit triggers
            if (mobileLimitBytes > 0) {
                val mobilePercent = (mobileUsed.toDouble() / mobileLimitBytes.toDouble()) * 100.0
                if (mobilePercent >= 100.0) {
                    if (!alertedMobile100) {
                        alertedMobile100 = true
                        sendAlertNotification(
                            title = "Mobile Data Limit Reached",
                            text = "Data Limit Reached! You have used 100% of your allocated Mobile data.",
                            id = 3001,
                            isHighPriority = true
                        )
                    }
                } else if (mobilePercent >= 90.0) {
                    if (!alertedMobile90) {
                        alertedMobile90 = true
                        sendAlertNotification(
                            title = "Mobile Data Warning",
                            text = "You have used 90% of your allocated Mobile data.",
                            id = 3002,
                            isHighPriority = false
                        )
                    }
                } else if (mobilePercent >= 50.0) {
                    if (!alertedMobile50) {
                        alertedMobile50 = true
                        sendAlertNotification(
                            title = "Mobile Data Alert",
                            text = "You have used 50% of your allocated Mobile data.",
                            id = 3003,
                            isHighPriority = false
                        )
                    }
                }
            }

            // Add-On limit triggers
            if (addonLimitBytes > 0) {
                val addonPercent = (addonUsed.toDouble() / addonLimitBytes.toDouble()) * 100.0
                if (addonPercent >= 100.0) {
                    if (!alertedAddon100) {
                        alertedAddon100 = true
                        sendAlertNotification(
                            title = "Add-On Limit Reached",
                            text = "Data Limit Reached! You have used 100% of your allocated Add-On data.",
                            id = 3004,
                            isHighPriority = true
                        )
                    }
                } else if (addonPercent >= 90.0) {
                    if (!alertedAddon90) {
                        alertedAddon90 = true
                        sendAlertNotification(
                            title = "Add-On Data Warning",
                            text = "You have used 90% of your allocated Add-On data.",
                            id = 3005,
                            isHighPriority = false
                        )
                    }
                } else if (addonPercent >= 50.0) {
                    if (!alertedAddon50) {
                        alertedAddon50 = true
                        sendAlertNotification(
                            title = "Add-On Data Alert",
                            text = "You have used 50% of your allocated Add-On data.",
                            id = 3006,
                            isHighPriority = false
                        )
                    }
                }
            }
        }
    }

    private fun sendAlertNotification(title: String, text: String, id: Int, isHighPriority: Boolean) {
        val channelId = if (isHighPriority) ALERTS_CHANNEL_ID else CHANNEL_ID
        val priority = if (isHighPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
        
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_network_icon)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        
        if (isHighPriority) {
            builder.setDefaults(Notification.DEFAULT_ALL)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, builder.build())
    }

    private fun convertToBytes(limit: Double, unit: String): Long {
        val factor = if (unit.uppercase(Locale.ROOT) == "GB") {
            1024L * 1024L * 1024L
        } else {
            1024L * 1024L
        }
        return (limit * factor).toLong()
    }

    private fun createSpeedIcon(downloadSpeed: Long): IconCompat? {
        if (!useBitmapIcon) return null
        return try {
            val kb = downloadSpeed / 1024.0
            val text: String
            val unit: String
            if (kb >= 1024.0 * 1024.0) {
                val gb = kb / (1024.0 * 1024.0)
                text = String.format(Locale.US, "%.1f", gb)
                unit = "gb/s"
            } else if (kb >= 1024.0) {
                val mb = kb / 1024.0
                text = if (mb >= 10.0) {
                    String.format(Locale.US, "%.0f", mb)
                } else {
                    String.format(Locale.US, "%.1f", mb)
                }
                unit = "mb/s"
            } else {
                text = kb.toInt().toString()
                unit = "kb/s"
            }

            // High-efficiency caching: skip bitmap recreation if speed string and unit has not changed
            if (text == lastSpeedText && unit == lastSpeedUnit && cachedSpeedIcon != null) {
                return cachedSpeedIcon
            }

            // Use full standard unit labels (kb/s, mb/s, gb/s)
            val displayUnit = unit

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

            // High-precision dynamic size adjustment (original scaled beautifully by 1.5x)
            val numTextSize = when (text.length) {
                1 -> 102f
                2 -> 87f
                3 -> 66f
                else -> 54f
            }
            paint.textSize = numTextSize
            // Draw digits beautifully balanced in upper section of the 144px canvas
            canvas.drawText(text, width / 2f, 75f, paint)

            // Draw full unit (kb/s, mb/s, gb/s) 1.5x larger in lower section
            paint.textSize = 51f
            canvas.drawText(displayUnit, width / 2f, 130f, paint)

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

    private fun formatBytesForNotification(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun buildSpeedNotification(dlBytes: Long, ulBytes: Long, mobileUsedBytes: Long, wifiUsedBytes: Long): Notification {
        val dlFormatted = formatSpeed(dlBytes)
        val ulFormatted = formatSpeed(ulBytes)
        val mobileFormatted = formatBytesForNotification(mobileUsedBytes)
        val wifiFormatted = formatBytesForNotification(wifiUsedBytes)

        return try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Down: $dlFormatted | Up: $ulFormatted")
                .setContentText("Mobile: $mobileFormatted | WiFi: $wifiFormatted")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)

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
            // Ultimate fallback with extremely basic safe properties to prevent crash on any android device/ROM
            try {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Down: $dlFormatted | Up: $ulFormatted")
                    .setContentText("Mobile: $mobileFormatted | WiFi: $wifiFormatted")
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSmallIcon(R.drawable.ic_network_icon)
                    .build()
            } catch (ex: Exception) {
                ex.printStackTrace()
                // Return dummy basic notification if everything else throws
                NotificationCompat.Builder(this, CHANNEL_ID).build()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel 1: Live Ticker
            val name = "Speed Monitor Service"
            val descriptionText = "Displays real-time Internet speeds in the status bar."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)

            // Channel 2: High Priority Warnings & Alerts
            val alertName = "Data Limit Alerts"
            val alertDesc = "Dispatches warnings and alerts when data usage limits are reached."
            val alertChannel = NotificationChannel(ALERTS_CHANNEL_ID, alertName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = alertDesc
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            String.format("%.1f MB/s", kb / 1024.0)
        } else {
            String.format("%.1f KB/s", kb)
        }
    }
}
