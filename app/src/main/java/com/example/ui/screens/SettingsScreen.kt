package com.example.ui.screens

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.services.SpeedMeterService
import com.example.ui.theme.AccentViolet
import com.example.ui.theme.AccentVioletDark
import com.example.util.BytesFormatter
import com.example.util.SpeedUnit
import com.example.ui.components.AppBackground
import com.example.ui.components.AppIcon
import com.example.ui.components.GlassCard
import com.example.ui.theme.AccentWarning
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.LiveGreenDark
import com.example.ui.theme.SpeedDown
import com.example.ui.theme.SpeedDownDark
import com.example.ui.theme.SpeedUp
import com.example.ui.theme.SpeedUpDark
import com.example.ui.theme.glassBorder
import com.example.ui.theme.glassColor
import com.example.ui.theme.isDarkModeActive
import com.example.ui.util.secondary
import com.example.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val storedMobileLimit by viewModel.mobileLimit.collectAsState()
    val storedMobileLimitUnit by viewModel.mobileLimitUnit.collectAsState()
    val isDark by viewModel.isDarkMode.collectAsState()
    val darkThemeActive = isDark ?: androidx.compose.foundation.isSystemInDarkTheme()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val accentKey by viewModel.accentKey.collectAsState()
    val speedTestServer by viewModel.speedTestServer.collectAsState()
    val liveSpeed by SpeedMeterService.liveSpeedFlow.collectAsState()
    val engineStatus by SpeedMeterService.engineStatusFlow.collectAsState()
    val serviceRunning by SpeedMeterService.isServiceRunning.collectAsState()

    var mobileLimitText by remember { mutableStateOf("") }
    var mobileLimitUnit by remember { mutableStateOf("GB") }
    var isLimitInitialized by remember { mutableStateOf(false) }

    var hasUsagePermission by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermission(context)) }

    var showResetConfirmation by remember { mutableStateOf(false) }
    var showPurgeConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(storedMobileLimit, storedMobileLimitUnit) {
        val limit = storedMobileLimit
        if (limit != null && !isLimitInitialized) {
            mobileLimitText = if (limit % 1.0 == 0.0) limit.toInt().toString() else limit.toString()
            mobileLimitUnit = storedMobileLimitUnit
            isLimitInitialized = true
        }
    }

    LaunchedEffect(Unit) {
        hasUsagePermission = checkUsageStatsPermission(context)
        hasNotificationPermission = checkNotificationPermission(context)
    }

    val liveAccent = if (isDarkModeActive()) LiveGreenDark else LiveGreen

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen_root")
    ) {
        AppBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 100.dp)
        ) {
            // ---------------- Header ----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_eyebrow),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondary()
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // ---------------- APPEARANCE ----------------
            SectionLabel(stringResource(R.string.section_appearance))
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("theme_card")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(glassColor),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIcon(
                                    resId = if (darkThemeActive) R.drawable.ic_dark_mode else R.drawable.ic_light_mode,
                                    contentDescription = stringResource(R.string.cd_theme),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.setting_dark_mode),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = when {
                                        isDark == null -> stringResource(R.string.dark_mode_system)
                                        isDark == true -> stringResource(R.string.dark_mode_on)
                                        else -> stringResource(R.string.dark_mode_off)
                                    },
                                    fontSize = 11.sp,
                                    color = secondary()
                                )
                            }
                        }
                        Switch(
                            checked = darkThemeActive,
                            onCheckedChange = { viewModel.toggleDarkMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                uncheckedTrackColor = glassColor
                            ),
                            modifier = Modifier.testTag("dark_mode_toggle_switch")
                        )
                    }

                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))

                    // ---- speed unit ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_speed_unit),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(
                                        if (speedUnit == SpeedUnit.BYTES) R.string.speed_unit_bytes
                                        else R.string.speed_unit_bits
                                    ),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                        SegmentedControl(
                            selectedValue = if (speedUnit == SpeedUnit.BYTES) "KB/s" else "Mbps",
                            options = listOf("KB/s", "Mbps"),
                            onSelected = { label ->
                                viewModel.updateSpeedUnit(
                                    if (label == "Mbps") SpeedUnit.BITS else SpeedUnit.BYTES
                                )
                            },
                            modifier = Modifier.testTag("speed_unit_segmented")
                        )
                    }

                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))

                    // ---- accent ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_accent),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.setting_accent_body),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                        AccentPicker(
                            selected = accentKey,
                            onSelected = { viewModel.updateAccent(it) }
                        )
                    }
                }
            }

            // ---------------- DAILY LIMIT ----------------
            SectionLabel(stringResource(R.string.section_limits), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("limits_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.limit_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.limit_body),
                        fontSize = 11.sp,
                        color = secondary()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = mobileLimitText,
                            onValueChange = { newVal ->
                                if (newVal.all { it.isDigit() || it == '.' }) {
                                    mobileLimitText = newVal
                                }
                            },
                            placeholder = { Text(stringResource(R.string.limit_placeholder)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = glassBorder,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("mobile_limit_input")
                        )
                        SegmentedControl(
                            selectedValue = mobileLimitUnit,
                            options = listOf("MB", "GB"),
                            onSelected = { unit -> mobileLimitUnit = unit },
                            modifier = Modifier.testTag("mobile_unit_segmented")
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.updateMobileLimit(
                                mobileLimitText.toDoubleOrNull() ?: 0.0,
                                mobileLimitUnit
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("save_limits_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_save_limit), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ---------------- SPEED TEST SERVER ----------------
            SectionLabel(stringResource(R.string.section_test_server), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("speed_test_server_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.setting_test_server),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.setting_test_server_body),
                            fontSize = 11.sp,
                            color = secondary()
                        )
                    }
                    ServerChoiceRow(
                        selected = speedTestServer,
                        onSelected = { viewModel.updateSpeedTestServer(it) }
                    )
                }
            }

            // ---------------- PERMISSIONS ----------------
            SectionLabel(stringResource(R.string.section_permissions), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("permissions_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PermissionRow(
                        iconRes = R.drawable.ic_shield,
                        title = stringResource(R.string.permission_usage),
                        subtitle = stringResource(
                            if (hasUsagePermission) R.string.permission_usage_active
                            else R.string.permission_usage_needed
                        ),
                        active = hasUsagePermission,
                        liveAccent = liveAccent,
                        buttonLabel = stringResource(R.string.action_grant),
                        onButton = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        buttonTag = "grant_usage_button"
                    )
                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                    PermissionRow(
                        iconRes = R.drawable.ic_notifications,
                        title = stringResource(R.string.permission_notifications),
                        subtitle = stringResource(
                            if (hasNotificationPermission) R.string.permission_notifications_active
                            else R.string.permission_notifications_needed
                        ),
                        active = hasNotificationPermission,
                        liveAccent = liveAccent,
                        buttonLabel = stringResource(R.string.action_grant),
                        onButton = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                try {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        },
                        buttonTag = "grant_notification_button"
                    )
                }
            }

            SectionLabel(stringResource(R.string.section_background), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("autostart_boot_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(LiveGreen.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_bolt,
                                contentDescription = null,
                                tint = LiveGreen,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.background_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.background_body),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { openAutostartSettings(context) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("configure_autostart_button")
                        ) {
                            Text(stringResource(R.string.action_autostart), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { openBatteryOptimizationSettings(context) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("configure_battery_button")
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_power,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_battery), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------- DATA MANAGEMENT ----------------
            SectionLabel(stringResource(R.string.section_data), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("data_management_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DataRow(
                        iconRes = R.drawable.ic_restore,
                        title = stringResource(R.string.data_reset_title),
                        subtitle = stringResource(R.string.data_reset_body),
                        buttonLabel = stringResource(R.string.action_reset),
                        danger = false,
                        onButton = { showResetConfirmation = true },
                        tag = "reset_today_button"
                    )
                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                    DataRow(
                        iconRes = R.drawable.ic_delete_forever,
                        title = stringResource(R.string.data_purge_title),
                        subtitle = stringResource(R.string.data_purge_body),
                        buttonLabel = stringResource(R.string.action_purge),
                        danger = true,
                        onButton = { showPurgeConfirmation = true },
                        tag = "purge_history_button"
                    )
                }
            }

            // ---------------- MEASUREMENT ENGINE ----------------
            SectionLabel(stringResource(R.string.section_engine), top = 18.dp)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("engine_diagnostics_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.engine_body),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    EngineRow(
                        stringResource(R.string.engine_status),
                        if (serviceRunning) {
                            stringResource(
                                if (liveSpeed.paused) R.string.engine_status_paused
                                else R.string.engine_status_running
                            )
                        } else {
                            stringResource(R.string.engine_status_stopped)
                        }
                    )
                    EngineRow(stringResource(R.string.engine_source), engineStatus.source.label)
                    EngineRow(
                        stringResource(R.string.engine_cadence),
                        stringResource(R.string.engine_cadence_value, liveSpeed.windowMs)
                    )
                    EngineRow(stringResource(R.string.engine_samples), liveSpeed.samplesTaken.toString())
                    EngineRow(stringResource(R.string.engine_network), networkTypeLabel(SpeedMeterService.networkTypeFlow.value))
                    EngineRow(
                        stringResource(R.string.engine_raw),
                        stringResource(
                            R.string.engine_raw_value,
                            BytesFormatter.format(liveSpeed.sessionRxBytes),
                            BytesFormatter.format(liveSpeed.sessionTxBytes)
                        )
                    )
                    if (engineStatus.interfaces.isNotEmpty()) {
                        HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                        Text(
                            text = stringResource(R.string.engine_interfaces),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            color = secondary()
                        )
                        engineStatus.interfaces.forEach { stat ->
                            EngineRow(
                                "• ${stat.name}",
                                "${BytesFormatter.format(stat.rx)} ↓ · ${BytesFormatter.format(stat.tx)} ↑"
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.engine_footer),
                        fontSize = 10.sp,
                        color = secondary().copy(alpha = 0.85f)
                    )
                }
            }

            // ---------------- ABOUT ----------------
            SectionLabel(stringResource(R.string.section_about), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.about_name),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.about_version, appVersion(context)),
                            fontSize = 11.sp,
                            color = secondary()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(glassColor),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.dialog_reset_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_reset_body), color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTodayUsage()
                    showResetConfirmation = false
                }) {
                        Text(
                            stringResource(R.string.action_reset),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                        Text(stringResource(R.string.action_cancel), color = secondary())
                    }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showPurgeConfirmation) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmation = false },
            title = { Text(stringResource(R.string.dialog_purge_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.dialog_purge_body), color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllHistory()
                    showPurgeConfirmation = false
                }) {
                        Text(
                            stringResource(R.string.action_purge_all),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirmation = false }) {
                        Text(stringResource(R.string.action_cancel), color = secondary())
                    }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun AccentPicker(selected: String, onSelected: (String) -> Unit) {
    val accents = listOf(
        "INK" to MaterialTheme.colorScheme.primary,
        "GREEN" to (if (isDarkModeActive()) SpeedDownDark else SpeedDown),
        "BLUE" to (if (isDarkModeActive()) SpeedUpDark else SpeedUp),
        "VIOLET" to (if (isDarkModeActive()) AccentVioletDark else AccentViolet)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accents.forEach { (key, color) ->
            val isSelected = key == selected
            Box(
                modifier = Modifier
                    .size(if (isSelected) 30.dp else 26.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onBackground
                        else glassBorder,
                        shape = RoundedCornerShape(100.dp)
                    )
                    .clickable { onSelected(key) }
                    .testTag("accent_$key"),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    AppIcon(
                        resId = R.drawable.ic_check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerChoiceRow(selected: String, onSelected: (String) -> Unit) {
    val options = listOf(
        "AUTO" to stringResource(R.string.server_auto),
        "CLOUDFLARE" to stringResource(R.string.server_cloudflare),
        "HETZNER" to stringResource(R.string.server_hetzner),
        "OVH" to stringResource(R.string.server_ovh)
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowOptions.forEach { (id, label) ->
                    val isSelected = id == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else glassColor
                            )
                            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                            .clickable { onSelected(id) }
                            .padding(vertical = 9.dp)
                            .testTag("server_$id"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = secondary())
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun networkTypeLabel(type: String): String = when (type) {
    SpeedMeterService.NETWORK_WIFI -> stringResource(R.string.transport_wifi)
    SpeedMeterService.NETWORK_MOBILE -> stringResource(R.string.transport_mobile_data)
    SpeedMeterService.NETWORK_ETHERNET -> stringResource(R.string.transport_ethernet)
    SpeedMeterService.NETWORK_VPN -> stringResource(R.string.transport_vpn)
    else -> stringResource(R.string.transport_offline)
}

@Composable
private fun SectionLabel(text: String, top: androidx.compose.ui.unit.Dp = 12.dp) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = secondary().copy(alpha = 0.8f),
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = top)
    )
}

@Composable
private fun PermissionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    active: Boolean,
    liveAccent: Color,
    buttonLabel: String,
    onButton: () -> Unit,
    buttonTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) liveAccent.copy(alpha = 0.12f) else AccentWarning.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    resId = iconRes,
                    contentDescription = null,
                    tint = if (active) liveAccent else AccentWarning,
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(text = subtitle, fontSize = 11.sp, color = secondary())
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (active) {
            Text(
                text = stringResource(R.string.permission_active),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = liveAccent
            )
        } else {
            Button(
                onClick = onButton,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(32.dp).testTag(buttonTag)
            ) {
                Text(buttonLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DataRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    buttonLabel: String,
    danger: Boolean,
    onButton: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.1f) else glassColor),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    resId = iconRes,
                    contentDescription = null,
                    tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(15.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(text = subtitle, fontSize = 11.sp, color = secondary())
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onButton,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                },
                contentColor = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.error
            ),
            border = if (!danger) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)) else null,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.height(32.dp).testTag(tag)
        ) {
            Text(buttonLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SegmentedControl(
    selectedValue: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(glassColor)
            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
            .height(40.dp)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selectedValue
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelected(option) }
                    .testTag("segment_$option"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private fun checkUsageStatsPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        mode == AppOpsManager.MODE_ALLOWED
    } else {
        true
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun appVersion(context: Context): String {
    return try {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}

private fun openBatteryOptimizationSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

private fun openAutostartSettings(context: Context) {
    val intents = listOf(
        Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(android.content.ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
        Intent().setComponent(android.content.ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent().setComponent(android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Intent().setComponent(android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
    )
    var opened = false
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            opened = true
            break
        } catch (e: Exception) {
            // try next
        }
    }
    if (!opened) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
