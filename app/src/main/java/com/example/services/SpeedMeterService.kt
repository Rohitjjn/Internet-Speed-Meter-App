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
import android.net.Network
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
import com.example.util.SpeedUnit
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

/** One live network interface as reported by the kernel (diagnostics panel). */
data class InterfaceStat(val name: String, val kind: InterfaceKind, val rx: Long, val tx: Long)

/** Which counter source the ticker is currently reading + the raw interfaces behind it. */
data class EngineStatus(
    val source: CounterSource = CounterSource.TRAFFIC_STATS,
    val interfaces: List<InterfaceStat> = emptyList(),
    val refreshedAtMs: Long = 0L
)

/**
 * The real-time engine of the app.
 *
 * ```
 *  ┌ 500 ms read loop (Dispatchers.Default) ────────────────────────────────┐
 *  │  TrafficStats ⇄ /proc/net/dev  →  SpeedEngine (pure maths)              │
 *  │  → publishes ~1 Hz: live speed, session totals, 60 s average, history   │
 *  │  → status-bar notification (only redrawn when the text actually changes)│
 *  └────────────────────────────────────────────────────────────────────────┘
 *  ┌ 15 s accounting loop (Dispatchers.IO) ─────────────────────────────────┐
 *  │  NetworkStatsManager (Usage access) OR engine attribution → Room        │
 *  └────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Design rules that make the numbers trustworthy (see [SpeedEngine] for the details):
 *
 *  1. **Two independent directions.** Download and upload always come from one coherent
 *     `(rx, tx)` read of the *same* source — never from two different counters, never
 *     from a cached/mirrored value. This is what fixes "upload and download show the
 *     same number".
 *  2. **Real windows.** Rate = bytes ÷ measured elapsed time (the read loop runs at
 *     500 ms and publishes every ~1 s), never bytes ÷ assumed 1000 ms.
 *  3. **No polling for the network type.** A [ConnectivityManager.NetworkCallback]
 *     pushes transport changes; the hot path does zero binder calls.
 *  4. **No fake data, ever.** A stale source, a suspended process or a counter that goes
 *     backwards re-anchors the engine instead of publishing an invented number.
 */
class SpeedMeterService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var tickerJob: Job? = null
    private var accountingJob: Job? = null

    /** The measurement core — pure Kotlin, only touched from the read loop coroutine. */
    private val engine = SpeedEngine()

    private lateinit var repository: DataUsageRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var networkStatsHelper: NetworkStatsHelper

    @Volatile
    private var pendingTransport: Transport = Transport.NONE

    @Volatile
    private var speedUnit: SpeedUnit = SpeedUnit.BYTES

    private var networkCallbackRegistered = false

    /** Consecutive FGS timeouts in this process — drives [onTimeout]'s backoff. */
    @Volatile
    private var timeoutRestartAttempts = 0

    /** Set once a (re)start lands, so a delayed retry does not stack services. */
    @Volatile
    private var timeoutRestarted = false

    // ----- daily accounting state -----
    private var hasUsageStatsPermission = false
    private var lastAccountedDay = ""
    private var resetOffsets = ResetOffsets()
    // Integrated fallback accumulators (used when "Usage access" is not granted):
    // real bytes attributed to the transport that was active per read interval.
    private var integratedMobileRx = 0L
    private var integratedMobileTx = 0L
    private var integratedWifiRx = 0L
    private var integratedWifiTx = 0L

    // ----- status-bar speed icon + notification throttling -----
    private var cachedSpeedIcon: IconCompat? = null
    private var lastSpeedText = ""
    private var lastSpeedUnit = ""
    private var lastNotificationKey = ""

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "speed_meter_channel"

        const val NETWORK_WIFI = "WIFI"
        const val NETWORK_MOBILE = "MOBILE"
        const val NETWORK_ETHERNET = "ETHERNET"
        const val NETWORK_VPN = "VPN"
        const val NETWORK_NONE = "NONE"

        /**
         * Read cadence. Two reads per published second: the bursty kernel counters are
         * never split across an assumed window, which is what made the old 1 Hz loop
         * publish stuttering duplicate values.
         */
        private const val READ_INTERVAL_MS = 500L
        private const val ACCOUNTING_PERIOD_MS = 15_000L

        /** First retry delay after an FGS timeout; doubles per consecutive timeout. */
        private const val TIMEOUT_RESTART_BASE_MS = 5_000L
        /** Never retry tighter than this, and give up after this many attempts. */
        private const val TIMEOUT_RESTART_MAX_MS = 120_000L
        private const val TIMEOUT_RESTART_MAX_ATTEMPTS = 6

        private val _liveSpeedFlow = MutableStateFlow(LiveSpeed())
        val liveSpeedFlow: StateFlow<LiveSpeed> = _liveSpeedFlow.asStateFlow()

        private val _networkTypeFlow = MutableStateFlow(NETWORK_NONE)
        val networkTypeFlow: StateFlow<String> = _networkTypeFlow.asStateFlow()

        /**
         * The same information as [networkTypeFlow], typed. The speed test watches this
         * to abort the moment the transport changes (Wi-Fi → mobile would otherwise bill
         * the user's data for a download burst).
         */
        private val _transportFlow = MutableStateFlow(Transport.NONE)
        val transportFlow: StateFlow<Transport> = _transportFlow.asStateFlow()

        private val _engineStatusFlow = MutableStateFlow(EngineStatus())
        val engineStatusFlow: StateFlow<EngineStatus> = _engineStatusFlow.asStateFlow()

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
        serviceScope.launch {
            preferencesManager.speedUnitFlow.collect { speedUnit = it }
        }
        registerNetworkCallback()
        pendingTransport = resolveTransport()
        _networkTypeFlow.value = pendingTransport.toNetworkLabel()
        _transportFlow.value = pendingTransport
        startSpeedTicker()
        startUsageAccounting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isServiceRunning.value = true
        // A (re)start succeeded, so the FGS-timeout backoff ladder starts over.
        timeoutRestartAttempts = 0
        timeoutRestarted = true
        startForegroundWithNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ (API 35): dataSync FGS get a 6-hour-per-day budget. When the
     * budget is exhausted the system calls this.
     *
     * Restarting immediately (what this used to do) is a trap on Android 15/16: if the
     * OS keeps refusing the start, the service spins in a tight loop and burns battery
     * and the remaining budget for nothing. So the restart is delayed by an exponential
     * backoff, and after [TIMEOUT_RESTART_MAX_ATTEMPTS] attempts the service stops
     * cleanly and lets the 60-minute WorkManager watchdog try again instead.
     */
    override fun onTimeout(startId: Int, timeoutType: Int) {
        val attempt = ++timeoutRestartAttempts
        if (attempt > TIMEOUT_RESTART_MAX_ATTEMPTS) {
            stopSelf()
            return
        }
        // Cleared until a start actually lands, so a delayed retry can never stack a
        // second service on top of one that already recovered on its own.
        timeoutRestarted = false
        serviceScope.launch {
            val delayMs = (TIMEOUT_RESTART_BASE_MS shl (attempt - 1).coerceAtMost(4))
                .coerceAtMost(TIMEOUT_RESTART_MAX_MS)
            delay(delayMs)
            // Only retry while this instance is still the one that timed out.
            if (!timeoutRestarted) {
                try {
                    ContextCompat.startForegroundService(
                        this@SpeedMeterService,
                        Intent(this@SpeedMeterService, SpeedMeterService::class.java)
                    )
                } catch (e: Exception) {
                    // Background start blocked: stop cleanly, the watchdog will retry.
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        // Persist the last published speed so the next service start shows a meaningful
        // status-bar number immediately instead of a 0 flash. Detached scope on purpose:
        // the service scope is cancelled below, and this is a fire-and-forget write.
        val last = engine.snapshot()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                preferencesManager.saveLastSpeed(last.steadyDownloadBps, last.steadyUploadBps)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        super.onDestroy()
        tickerJob?.cancel()
        accountingJob?.cancel()
        unregisterNetworkCallback()
        serviceJob.cancel()
        _isServiceRunning.value = false
        _liveSpeedFlow.value = LiveSpeed()
        _transportFlow.value = Transport.NONE
        _networkTypeFlow.value = NETWORK_NONE
        lastNotificationKey = ""
        cachedSpeedIcon = null
        lastSpeedText = ""
        lastSpeedUnit = ""
    }

    // ============================== NETWORK TYPE (PUSH) ==============================

    /**
     * Transport changes are *pushed* by the OS instead of polled once per second, so a
     * Wi-Fi ⇄ mobile switch is attributed to the correct counter immediately and the
     * 500 ms loop performs no binder calls at all.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged()

        override fun onLost(network: Network) = onNetworkChanged()

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            onNetworkChanged()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            // Older/odd ROMs: the ticker falls back to its own (cheap) resolution.
            e.printStackTrace()
        }
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        networkCallbackRegistered = false
    }

    private fun onNetworkChanged() {
        val transport = resolveTransport()
        pendingTransport = transport
        // The UI chip updates instantly; the accounting attribution follows within one
        // read interval so the engine is still only ever touched by the read loop.
        _networkTypeFlow.value = transport.toNetworkLabel()
        _transportFlow.value = transport
    }

    private fun resolveTransport(): Transport {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Transport.NONE
            val network = cm.activeNetwork ?: return Transport.NONE
            val capabilities = cm.getNetworkCapabilities(network) ?: return Transport.NONE
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.MOBILE
                else -> Transport.NONE
            }
        } catch (e: Exception) {
            Transport.NONE
        }
    }

    private fun Transport.toNetworkLabel(): String = when (this) {
        Transport.WIFI -> NETWORK_WIFI
        Transport.MOBILE -> NETWORK_MOBILE
        Transport.ETHERNET -> NETWORK_ETHERNET
        Transport.VPN -> NETWORK_VPN
        Transport.NONE -> NETWORK_NONE
    }

    // ================================ SPEED TICKER ================================

    private fun startSpeedTicker() {
        tickerJob = serviceScope.launch(Dispatchers.Default) {
            var source = CounterSource.TRAFFIC_STATS
            var lastSample = TrafficCounterReader.read(source)

            // Cold-start source pick: TrafficStats restricted (UNSUPPORTED or 0 on some
            // OEM ROMs) while the kernel counters are alive → start on /proc/net/dev.
            if (!lastSample.isSupported || lastSample.total <= 0L) {
                val kernel = TrafficCounterReader.read(CounterSource.PROC_NET_DEV)
                if (kernel.isSupported && kernel.total > 0L) {
                    source = CounterSource.PROC_NET_DEV
                    lastSample = kernel
                }
            }
            engine.seed(lastSample)

            // Restore the last known speed so the notification is never a 0 flash.
            try {
                val lastSpeed = preferencesManager.lastSpeedFlow.first()
                engine.restoreLastKnown(lastSpeed.first, lastSpeed.second)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            var shadowSample = TrafficCounterReader.read(TrafficCounterReader.other(source))
            var stagnantReads = 0
            var readsSinceProbe = 0
            var readsSinceResolve = 0
            var publishesSinceStatus = 0

            publishLive(engine.snapshot())
            publishEngineStatus(source)

            var nextReadAt = SystemClock.elapsedRealtime() + READ_INTERVAL_MS
            while (true) {
                val now = SystemClock.elapsedRealtime()
                delay((nextReadAt - now).coerceIn(10L, 5_000L))
                nextReadAt += READ_INTERVAL_MS
                val readAt = SystemClock.elapsedRealtime()
                if (readAt > nextReadAt + READ_INTERVAL_MS) nextReadAt = readAt + READ_INTERVAL_MS

                // Self-healing: on ROMs where the connectivity callback is unreliable,
                // re-resolve the transport once every 30 s (one binder call, not one per tick).
                readsSinceResolve++
                if (readsSinceResolve >= 60) {
                    readsSinceResolve = 0
                    val resolved = resolveTransport()
                    if (resolved != pendingTransport) {
                        pendingTransport = resolved
                        _networkTypeFlow.value = resolved.toNetworkLabel()
                        _transportFlow.value = resolved
                    }
                }

                // Apply a transport change pushed by the NetworkCallback.
                val pushed = pendingTransport
                if (pushed != engine.transport()) {
                    engine.setTransport(pushed)
                    publishLive(engine.snapshot())
                }

                val reading = TrafficCounterReader.read(source, readAt)
                stagnantReads = if (SourceHealth.progressed(reading, lastSample)) 0 else stagnantReads + 1
                readsSinceProbe++

                // ---- source health: never keep reading a dead counter ----
                // The decision itself lives in SourceHealth (pure, unit-tested): it is
                // throttled and only ever switches on positive evidence from the other
                // source, which is what stopped the old cross-source flapping.
                val activeUsable = SourceHealth.isUsable(reading)
                if (SourceHealth.shouldProbe(
                        SourceHealth.Symptoms(
                            activeUsable = activeUsable,
                            stagnantReads = stagnantReads,
                            readsSinceProbe = readsSinceProbe
                        )
                    )
                ) {
                    readsSinceProbe = 0
                    val other = TrafficCounterReader.other(source)
                    val otherReading = TrafficCounterReader.read(other, readAt)
                    val otherMoving = SourceHealth.progressed(otherReading, shadowSample)
                    shadowSample = otherReading
                    if (SourceHealth.decide(
                            SourceHealth.Evidence(
                                activeUsable = activeUsable,
                                otherUsable = SourceHealth.isUsable(otherReading),
                                otherTotal = otherReading.total,
                                otherProgressed = otherMoving
                            )
                        ) == SourceHealth.Decision.SWITCH
                    ) {
                        source = other
                        lastSample = otherReading
                        engine.seed(otherReading) // fresh anchor: no cross-source delta
                        stagnantReads = 0
                        publishEngineStatus(source)
                        continue
                    }
                }

                lastSample = reading
                val published = engine.onSample(reading)
                if (published != null) {
                    publishLive(published)
                    publishesSinceStatus++
                    if (publishesSinceStatus >= 8) { // ~8 s
                        publishesSinceStatus = 0
                        publishEngineStatus(source)
                    }
                }
            }
        }
    }

    private fun publishLive(live: LiveSpeed) {
        _liveSpeedFlow.value = live
        // The chip mirrors the transport the OS pushed (the engine catches up within one
        // read interval), so a Wi-Fi ⇄ mobile switch never flickers back to the old value.
        _networkTypeFlow.value = pendingTransport.toNetworkLabel()
        _transportFlow.value = pendingTransport
        updateNotification(live)
    }

    private fun publishEngineStatus(source: CounterSource) {
        val interfaces = try {
            TrafficCounterReader.readInterfaceTotals()
                .map { (name, bytes) ->
                    InterfaceStat(name, TrafficCounterReader.interfaceKind(name), bytes.rx, bytes.tx)
                }
                .sortedByDescending { it.rx + it.tx }
                .take(6)
        } catch (e: Exception) {
            emptyList()
        }
        _engineStatusFlow.value = EngineStatus(
            source = source,
            interfaces = interfaces,
            refreshedAtMs = SystemClock.elapsedRealtime()
        )
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
            engine.drainAttributed() // drop anything attributed before this process started
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
            // Real bytes the engine attributed to each transport since the previous
            // pass. The engine remembers what it already handed over, so a re-seed in
            // between (service restart / counter-source switch) contributes nothing
            // instead of producing a negative or double-counted delta.
            val delta = engine.drainAttributed()
            val wifiDelta = (delta[Transport.WIFI] ?: TransferBytes()) +
                (delta[Transport.ETHERNET] ?: TransferBytes()) +
                (delta[Transport.VPN] ?: TransferBytes())
            val mobileDelta = delta[Transport.MOBILE] ?: TransferBytes()

            integratedWifiRx += wifiDelta.rx
            integratedWifiTx += wifiDelta.tx
            integratedMobileRx += mobileDelta.rx
            integratedMobileTx += mobileDelta.tx

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
            // Persist the live speed for the next service start (throttled to this loop).
            val live = engine.snapshot()
            preferencesManager.saveLastSpeed(live.steadyDownloadBps, live.steadyUploadBps)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildTodayEntity(): DataUsageEntity {
        val today = currentDay()
        return DataUsageEntity(
            today,
            integratedMobileRx,
            integratedMobileTx,
            integratedWifiRx,
            integratedWifiTx
        )
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
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildSpeedNotification(engine.snapshot())
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

    /**
     * Only pushes a new notification when something the user can actually see changed —
     * on an idle connection that is zero system calls per second instead of one.
     */
    private fun updateNotification(live: LiveSpeed) {
        val key = buildString {
            append(live.steadyDownloadBps).append('|')
            append(live.steadyUploadBps).append('|')
            append(live.transport).append('|')
            append(_todayUsageFlow.value.total).append('|')
            append(live.paused).append('|')
            append(speedUnit)
        }
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildSpeedNotification(live))
        } catch (e: Exception) {
            // Notifications disabled — ticker keeps running, UI flows are unaffected.
        }
    }

    private fun buildSpeedNotification(live: LiveSpeed): Notification {
        val networkLabel = when (live.transport) {
            Transport.WIFI -> getString(R.string.transport_wifi)
            Transport.ETHERNET -> getString(R.string.transport_ethernet)
            Transport.VPN -> getString(R.string.transport_vpn)
            Transport.MOBILE -> getString(R.string.transport_mobile)
            Transport.NONE -> getString(R.string.transport_offline)
        }
        val todayTotal = _todayUsageFlow.value.total
        val title = "↓ ${SpeedFormatter.format(live.steadyDownloadBps, speedUnit)}  " +
            "↑ ${SpeedFormatter.format(live.steadyUploadBps, speedUnit)}"
        val text = buildString {
            append(getString(R.string.notification_text, networkLabel, BytesFormatter.format(todayTotal)))
            if (live.paused) {
                append(" · ")
                append(getString(R.string.notification_paused))
            }
        }

        return try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openAppIntent())

            val speedIcon = createSpeedIcon(live.steadyDownloadBps)
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
     * changes, so the read loop stays cheap.
     */
    private fun createSpeedIcon(downloadSpeed: Long): IconCompat? {
        return try {
            val (text, unit) = SpeedFormatter.split(downloadSpeed, speedUnit)
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

            // Must stay readable at status-bar size in BOTH display modes (e.g. "842",
            // "12.4", "104.9") without clipping the 144 px icon canvas.
            paint.textSize = when (text.length) {
                1 -> 102f
                2 -> 87f
                3 -> 66f
                4 -> 52f
                else -> 42f
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
