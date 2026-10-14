package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.util.SpeedUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "speed_meter_prefs")

/**
 * Baseline captured when the user resets today's counters.
 * Daily usage = max(0, systemSinceMidnight - baseline) while the baseline date matches today.
 */
data class ResetOffsets(
    val date: String = "",
    val mobileRx: Long = 0L,
    val mobileTx: Long = 0L,
    val wifiRx: Long = 0L,
    val wifiTx: Long = 0L
) {
    fun appliesTo(day: String): Boolean = date == day
}

/**
 * One finished active speed test. Values are **real measurements** (bytes actually
 * transferred over the socket / round-trip times actually observed) — never estimates.
 */
data class SpeedTestResult(
    val atMillis: Long,
    val downloadBps: Double,
    val uploadBps: Double,
    val latencyMs: Long,
    val jitterMs: Long,
    val server: String
) {
    companion object {
        private const val FIELD_SEPARATOR = '|'
        private const val ENTRY_SEPARATOR = '\n'

        /** Compact, dependency-free serialization: one result per line. */
        fun encode(list: List<SpeedTestResult>): String = list.joinToString(ENTRY_SEPARATOR.toString()) {
            buildString {
                append(it.atMillis)
                append(FIELD_SEPARATOR)
                append(it.downloadBps.toLong())
                append(FIELD_SEPARATOR)
                append(it.uploadBps.toLong())
                append(FIELD_SEPARATOR)
                append(it.latencyMs)
                append(FIELD_SEPARATOR)
                append(it.jitterMs)
                append(FIELD_SEPARATOR)
                append(it.server.replace(FIELD_SEPARATOR, ' '))
            }
        }

        fun decode(raw: String?): List<SpeedTestResult> {
            if (raw.isNullOrBlank()) return emptyList()
            val results = ArrayList<SpeedTestResult>()
            for (line in raw.split(ENTRY_SEPARATOR)) {
                if (line.isBlank()) continue
                val parts = line.split(FIELD_SEPARATOR)
                if (parts.size < 6) continue
                val at = parts[0].toLongOrNull() ?: continue
                val down = parts[1].toDoubleOrNull() ?: continue
                val up = parts[2].toDoubleOrNull() ?: continue
                val latency = parts[3].toLongOrNull() ?: continue
                val jitter = parts[4].toLongOrNull() ?: continue
                results += SpeedTestResult(at, down, up, latency, jitter, parts[5])
            }
            return results
        }
    }
}

/**
 * All user preferences, on top of one Preferences DataStore. The store is injectable so
 * tests can give every case its own file instead of sharing (and having to restore) the
 * process-wide singleton; production code uses the default.
 */
class PreferencesManager(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.dataStore
) {

    companion object {
        val KEY_IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
        val KEY_MOBILE_LIMIT = doublePreferencesKey("mobile_limit")
        val KEY_MOBILE_LIMIT_UNIT = stringPreferencesKey("mobile_limit_unit")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_RESET_DATE = stringPreferencesKey("reset_date")
        val KEY_RESET_MOBILE_RX = longPreferencesKey("reset_mobile_rx")
        val KEY_RESET_MOBILE_TX = longPreferencesKey("reset_mobile_tx")
        val KEY_RESET_WIFI_RX = longPreferencesKey("reset_wifi_rx")
        val KEY_RESET_WIFI_TX = longPreferencesKey("reset_wifi_tx")
        val KEY_LAST_DISMISSED_MOBILE_ALERT = longPreferencesKey("last_dismissed_mobile_alert")
        val KEY_LAST_DISMISSED_ALERT_DATE = stringPreferencesKey("last_dismissed_alert_date")
        val KEY_SPEED_UNIT = stringPreferencesKey("speed_unit")
        val KEY_ACCENT = stringPreferencesKey("accent_color")
        val KEY_LAST_SPEED_DOWN = longPreferencesKey("last_speed_down")
        val KEY_LAST_SPEED_UP = longPreferencesKey("last_speed_up")
        val KEY_SPEED_TEST_RESULTS = stringPreferencesKey("speed_test_results")
        val KEY_TEST_SERVER = stringPreferencesKey("test_server")
        val KEY_TEST_SERVER_URL = stringPreferencesKey("test_server_url")
        val KEY_TEST_SERVER_FLAVOUR = stringPreferencesKey("test_server_flavour")
        val KEY_METER_PAUSED_BY_USER = booleanPreferencesKey("meter_paused_by_user")
        val KEY_MONTHLY_LIMIT = doublePreferencesKey("monthly_limit")
        val KEY_MONTHLY_LIMIT_UNIT = stringPreferencesKey("monthly_limit_unit")
        val KEY_LAST_DISMISSED_MONTHLY_ALERT = longPreferencesKey("last_dismissed_monthly_alert")
        val KEY_LAST_DISMISSED_MONTHLY_ALERT_MONTH = stringPreferencesKey("last_dismissed_monthly_alert_month")
        val KEY_REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
    }

    /**
     * DataStore can fail to read (corrupt file, IO error). Without this guard the whole
     * preferences flow would crash the collector — so a read error degrades to defaults.
     */
    private val data: Flow<Preferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    // ---------- Theme ----------

    val isDarkModeFlow: Flow<Boolean?> = data.map { it[KEY_IS_DARK_MODE] }

    suspend fun setDarkMode(enabled: Boolean) {
        store.edit { it[KEY_IS_DARK_MODE] = enabled }
    }

    // ---------- Accent ----------

    /** Stored as a key string so the data layer stays independent of the UI theme. */
    val accentKeyFlow: Flow<String> = data.map { it[KEY_ACCENT] ?: "INK" }

    suspend fun setAccentKey(key: String) {
        store.edit { it[KEY_ACCENT] = key }
    }

    // ---------- Speed display unit ----------

    val speedUnitFlow: Flow<SpeedUnit> = data.map { SpeedUnit.fromKey(it[KEY_SPEED_UNIT]) }

    suspend fun setSpeedUnit(unit: SpeedUnit) {
        store.edit { it[KEY_SPEED_UNIT] = unit.key }
    }

    // ---------- Last known live speed (status-bar continuity) ----------

    val lastSpeedFlow: Flow<Pair<Long, Long>> = data.map {
        (it[KEY_LAST_SPEED_DOWN] ?: 0L) to (it[KEY_LAST_SPEED_UP] ?: 0L)
    }

    suspend fun saveLastSpeed(downloadBps: Long, uploadBps: Long) {
        store.edit {
            it[KEY_LAST_SPEED_DOWN] = downloadBps.coerceAtLeast(0L)
            it[KEY_LAST_SPEED_UP] = uploadBps.coerceAtLeast(0L)
        }
    }

    // ---------- Daily mobile data limit (alerts) ----------

    val mobileLimitFlow: Flow<Double> = data.map { it[KEY_MOBILE_LIMIT] ?: 0.0 }

    val mobileLimitUnitFlow: Flow<String> = data.map { it[KEY_MOBILE_LIMIT_UNIT] ?: "GB" }

    suspend fun saveMobileLimit(limit: Double, unit: String) {
        store.edit {
            it[KEY_MOBILE_LIMIT] = limit
            it[KEY_MOBILE_LIMIT_UNIT] = unit
        }
    }

    // ---------- Meter paused by the user ----------

    /**
     * `true` while the user has switched the meter off. Every restart path (app resume,
     * boot receiver, watchdog worker, timeout retry) asks this before starting the
     * foreground service, so "paused by you" really stays paused until the user resumes.
     */
    val meterPausedByUserFlow: Flow<Boolean> = data.map { it[KEY_METER_PAUSED_BY_USER] ?: false }

    suspend fun setMeterPausedByUser(paused: Boolean) {
        store.edit { it[KEY_METER_PAUSED_BY_USER] = paused }
    }

    // ---------- Reduced motion ----------

    /** `true` = the user asked the app to skip its decorative animations (gauge sweep, breathing labels). */
    val reducedMotionFlow: Flow<Boolean> = data.map { it[KEY_REDUCED_MOTION] ?: false }

    suspend fun setReducedMotion(enabled: Boolean) {
        store.edit { it[KEY_REDUCED_MOTION] = enabled }
    }

    // ---------- Monthly mobile data limit (warn-only, optional) ----------

    val monthlyLimitFlow: Flow<Double> = data.map { it[KEY_MONTHLY_LIMIT] ?: 0.0 }

    val monthlyLimitUnitFlow: Flow<String> = data.map { it[KEY_MONTHLY_LIMIT_UNIT] ?: "GB" }

    suspend fun saveMonthlyLimit(limit: Double, unit: String) {
        store.edit {
            it[KEY_MONTHLY_LIMIT] = limit
            it[KEY_MONTHLY_LIMIT_UNIT] = unit
        }
    }

    /** Highest monthly tier (50/90/100) the user has dismissed, and for which `yyyy-MM`. */
    val lastDismissedMonthlyAlertFlow: Flow<Long> = data.map { it[KEY_LAST_DISMISSED_MONTHLY_ALERT] ?: 0L }

    val lastDismissedMonthlyAlertMonthFlow: Flow<String> = data.map {
        it[KEY_LAST_DISMISSED_MONTHLY_ALERT_MONTH] ?: ""
    }

    suspend fun saveDismissedMonthlyAlert(level: Long, month: String) {
        store.edit {
            it[KEY_LAST_DISMISSED_MONTHLY_ALERT] = level
            it[KEY_LAST_DISMISSED_MONTHLY_ALERT_MONTH] = month
        }
    }

    // ---------- Onboarding ----------

    val isOnboardingCompletedFlow: Flow<Boolean> = data.map {
        it[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    // ---------- "Reset today" baselines ----------

    val resetOffsetsFlow: Flow<ResetOffsets> = data.map { prefs ->
        ResetOffsets(
            date = prefs[KEY_RESET_DATE] ?: "",
            mobileRx = prefs[KEY_RESET_MOBILE_RX] ?: 0L,
            mobileTx = prefs[KEY_RESET_MOBILE_TX] ?: 0L,
            wifiRx = prefs[KEY_RESET_WIFI_RX] ?: 0L,
            wifiTx = prefs[KEY_RESET_WIFI_TX] ?: 0L
        )
    }

    suspend fun saveResetOffsets(offsets: ResetOffsets) {
        store.edit {
            it[KEY_RESET_DATE] = offsets.date
            it[KEY_RESET_MOBILE_RX] = offsets.mobileRx
            it[KEY_RESET_MOBILE_TX] = offsets.mobileTx
            it[KEY_RESET_WIFI_RX] = offsets.wifiRx
            it[KEY_RESET_WIFI_TX] = offsets.wifiTx
        }
    }

    suspend fun clearResetOffsets() {
        store.edit {
            it[KEY_RESET_DATE] = ""
            it[KEY_RESET_MOBILE_RX] = 0L
            it[KEY_RESET_MOBILE_TX] = 0L
            it[KEY_RESET_WIFI_RX] = 0L
            it[KEY_RESET_WIFI_TX] = 0L
        }
    }

    // ---------- In-app alert dismissal (daily limit) ----------

    val lastDismissedMobileAlertFlow: Flow<Long> = data.map {
        it[KEY_LAST_DISMISSED_MOBILE_ALERT] ?: 0L
    }

    val lastDismissedAlertDateFlow: Flow<String> = data.map {
        it[KEY_LAST_DISMISSED_ALERT_DATE] ?: ""
    }

    suspend fun saveDismissedMobileAlert(level: Long, date: String) {
        store.edit {
            it[KEY_LAST_DISMISSED_MOBILE_ALERT] = level
            it[KEY_LAST_DISMISSED_ALERT_DATE] = date
        }
    }

    // ---------- Active speed-test history ----------

    val speedTestResultsFlow: Flow<List<SpeedTestResult>> = data.map {
        SpeedTestResult.decode(it[KEY_SPEED_TEST_RESULTS])
    }

    suspend fun saveSpeedTestResult(result: SpeedTestResult, keep: Int = 20) {
        store.edit { prefs ->
            val existing = SpeedTestResult.decode(prefs[KEY_SPEED_TEST_RESULTS])
            val updated = (listOf(result) + existing).take(keep)
            prefs[KEY_SPEED_TEST_RESULTS] = SpeedTestResult.encode(updated)
        }
    }

    suspend fun clearSpeedTestResults() {
        store.edit { it[KEY_SPEED_TEST_RESULTS] = "" }
    }

    // ---------- Active speed-test endpoint ----------

    /**
     * Which measurement endpoint the opt-in test uses: `AUTO` (Cloudflare, fall back to
     * LibreSpeed when the host is blocked), an explicit built-in host, or `CUSTOM`.
     */
    val testServerFlow: Flow<String> = data.map { it[KEY_TEST_SERVER] ?: "AUTO" }

    val testServerUrlFlow: Flow<String> = data.map { it[KEY_TEST_SERVER_URL] ?: "" }

    val testServerFlavourFlow: Flow<String> = data.map {
        it[KEY_TEST_SERVER_FLAVOUR] ?: "CLOUDFLARE"
    }

    suspend fun saveTestServer(preference: String, customUrl: String, flavour: String) {
        store.edit {
            it[KEY_TEST_SERVER] = preference
            it[KEY_TEST_SERVER_URL] = customUrl
            it[KEY_TEST_SERVER_FLAVOUR] = flavour
        }
    }
}
