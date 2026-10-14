package com.example.ui.screens

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.services.SpeedMeterService
import com.example.services.SpeedTestServers
import com.example.ui.components.AppBackground
import com.example.ui.components.AppIcon
import com.example.ui.components.GlassCard
import com.example.ui.theme.AccentTheme
import com.example.ui.theme.AccentViolet
import com.example.ui.theme.AccentVioletDark
import com.example.ui.theme.AccentWarning
import com.example.ui.theme.DarkPrimary
import com.example.ui.theme.LightPrimary
import com.example.ui.theme.dynamicPrimary
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
import com.example.util.BytesFormatter
import com.example.util.SpeedUnit

/** Widest the settings column gets; beyond this it is centred (tablets, landscape). */
private val SettingsMaxWidth = 720.dp

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val storedMobileLimit by viewModel.mobileLimit.collectAsState()
    val storedMobileLimitUnit by viewModel.mobileLimitUnit.collectAsState()
    val storedMonthlyLimit by viewModel.monthlyLimit.collectAsState()
    val storedMonthlyLimitUnit by viewModel.monthlyLimitUnit.collectAsState()
    val isDark by viewModel.isDarkMode.collectAsState()
    val darkThemeActive = isDark ?: isSystemInDarkTheme()
    val speedUnit by viewModel.speedUnit.collectAsState()
    val accentKey by viewModel.accentKey.collectAsState()
    val liveSpeed by SpeedMeterService.liveSpeedFlow.collectAsState()
    val engineStatus by SpeedMeterService.engineStatusFlow.collectAsState()
    val serviceRunning by SpeedMeterService.isServiceRunning.collectAsState()
    val meterPausedByUser by viewModel.meterPausedByUser.collectAsState()
    val reducedMotion by viewModel.reducedMotion.collectAsState()
    // TalkBack reads the switch's state as "paused by you" / "running", not just "on/off".
    val pausedStateText = stringResource(R.string.engine_status_paused_by_user)
    val runningStateText = stringResource(R.string.engine_status_running)
    // Collected (not read once with `.value`), otherwise this row never refreshes.
    val networkType by SpeedMeterService.networkTypeFlow.collectAsState()
    // The endpoint choice is read straight from the store: what is highlighted is what is
    // saved, there is no second copy that can drift.
    val storedTestServer by viewModel.testServer.collectAsState()
    val storedTestServerUrl by viewModel.testServerUrl.collectAsState()
    val storedTestServerFlavour by viewModel.testServerFlavour.collectAsState()

    var mobileLimitText by remember { mutableStateOf("") }
    var mobileLimitUnit by remember { mutableStateOf("GB") }
    var isLimitInitialized by remember { mutableStateOf(false) }
    var monthlyLimitText by remember { mutableStateOf("") }
    var monthlyLimitUnit by remember { mutableStateOf("GB") }
    var isMonthlyLimitInitialized by remember { mutableStateOf(false) }

    // Only the custom URL needs a draft (typing is incremental); it is seeded once from
    // the stored value and saved with an explicit tap.
    var customServerUrl by remember { mutableStateOf("") }
    var isCustomUrlInitialized by remember { mutableStateOf(false) }

    var hasUsagePermission by remember { mutableStateOf(checkUsageStatsPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(checkNotificationPermission(context)) }

    var showResetConfirmation by remember { mutableStateOf(false) }
    var showPurgeConfirmation by remember { mutableStateOf(false) }

    var serverCardExpanded by rememberSaveable { mutableStateOf(false) }
    var engineCardExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(storedMobileLimit, storedMobileLimitUnit) {
        val limit = storedMobileLimit
        if (limit != null && !isLimitInitialized) {
            mobileLimitText = if (limit % 1.0 == 0.0) limit.toInt().toString() else limit.toString()
            mobileLimitUnit = storedMobileLimitUnit
            isLimitInitialized = true
        }
    }

    LaunchedEffect(storedMonthlyLimit, storedMonthlyLimitUnit) {
        val limit = storedMonthlyLimit
        if (limit != null && !isMonthlyLimitInitialized) {
            monthlyLimitText = if (limit % 1.0 == 0.0) limit.toInt().toString() else limit.toString()
            monthlyLimitUnit = storedMonthlyLimitUnit
            isMonthlyLimitInitialized = true
        }
    }

    LaunchedEffect(storedTestServerUrl) {
        if (!isCustomUrlInitialized && storedTestServerUrl.isNotBlank()) {
            customServerUrl = storedTestServerUrl
            isCustomUrlInitialized = true
        }
    }

    // Re-check the permission rows every time the user comes back from the system
    // settings — not only on first composition.
    LifecycleResumeEffect(Unit) {
        hasUsagePermission = checkUsageStatsPermission(context)
        hasNotificationPermission = checkNotificationPermission(context)
        onPauseOrDispose { }
    }

    val liveAccent = if (isDarkModeActive()) LiveGreenDark else LiveGreen

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen_root")
    ) {
        AppBackground()

        // A settings list reads best as one column of limited width: on tablets and in
        // landscape it is centred instead of stretching every card across the screen.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = SettingsMaxWidth)
                .fillMaxWidth()
                .fillMaxHeight()
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
            SectionLabel(stringResource(R.string.settings_section_appearance))
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("theme_card")) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
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
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_dark_mode),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = when (isDark) {
                                        null -> stringResource(R.string.settings_dark_mode_system)
                                        true -> stringResource(R.string.settings_dark_mode_on)
                                        else -> stringResource(R.string.settings_dark_mode_off)
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
                                text = stringResource(R.string.settings_speed_unit),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (speedUnit == SpeedUnit.BYTES) {
                                    stringResource(R.string.settings_speed_unit_bytes_hint)
                                } else {
                                    stringResource(R.string.settings_speed_unit_bits_hint)
                                },
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
                                text = stringResource(R.string.settings_accent),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.settings_accent_hint),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                        AccentPicker(
                            selected = accentKey,
                            onSelected = { viewModel.updateAccent(it) }
                        )
                    }

                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))

                    // ---- reduced motion ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_reduced_motion),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.settings_reduced_motion_hint),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                        Switch(
                            checked = reducedMotion,
                            onCheckedChange = { viewModel.setReducedMotion(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                uncheckedTrackColor = glassColor
                            ),
                            modifier = Modifier.testTag("reduced_motion_switch")
                        )
                    }
                }
            }

            // ---------------- DAILY LIMIT ----------------
            SectionLabel(stringResource(R.string.settings_section_alerts), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("limits_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_limit_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.settings_limit_hint),
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
                            placeholder = { Text(stringResource(R.string.settings_limit_placeholder)) },
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
                        Text(stringResource(R.string.settings_save_limit), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    val savedLimit = storedMobileLimit
                    Text(
                        text = if (savedLimit == null || savedLimit <= 0.0) {
                            stringResource(R.string.settings_limit_saved_none)
                        } else {
                            stringResource(
                                R.string.settings_limit_saved,
                                if (savedLimit % 1.0 == 0.0) savedLimit.toInt().toString() else savedLimit.toString(),
                                storedMobileLimitUnit
                            )
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = secondary(),
                        modifier = Modifier.testTag("saved_limit_label")
                    )

                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))

                    // ---- optional monthly limit (same warn-only rule, per calendar month) ----
                    Text(
                        text = stringResource(R.string.settings_monthly_limit_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.settings_monthly_limit_hint),
                        fontSize = 11.sp,
                        color = secondary()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = monthlyLimitText,
                            onValueChange = { newVal ->
                                if (newVal.all { it.isDigit() || it == '.' }) {
                                    monthlyLimitText = newVal
                                }
                            },
                            placeholder = { Text(stringResource(R.string.settings_monthly_limit_placeholder)) },
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
                                .testTag("monthly_limit_input")
                        )
                        SegmentedControl(
                            selectedValue = monthlyLimitUnit,
                            options = listOf("MB", "GB"),
                            onSelected = { unit -> monthlyLimitUnit = unit },
                            modifier = Modifier.testTag("monthly_unit_segmented")
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.updateMonthlyLimit(
                                monthlyLimitText.toDoubleOrNull() ?: 0.0,
                                monthlyLimitUnit
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("save_monthly_limit_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        border = BorderStroke(1.dp, glassBorder),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_save,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_save_monthly_limit), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    val savedMonthly = storedMonthlyLimit
                    Text(
                        text = if (savedMonthly == null || savedMonthly <= 0.0) {
                            stringResource(R.string.settings_monthly_limit_saved_none)
                        } else {
                            stringResource(
                                R.string.settings_monthly_limit_saved,
                                if (savedMonthly % 1.0 == 0.0) savedMonthly.toInt().toString() else savedMonthly.toString(),
                                storedMonthlyLimitUnit
                            )
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = secondary(),
                        modifier = Modifier.testTag("saved_monthly_limit_label")
                    )
                }
            }

            // ---------------- PERMISSIONS ----------------
            SectionLabel(stringResource(R.string.settings_section_permissions), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("permissions_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PermissionRow(
                        iconRes = R.drawable.ic_shield,
                        title = stringResource(R.string.usage_access_title),
                        subtitle = if (hasUsagePermission) {
                            stringResource(R.string.settings_usage_access_active)
                        } else {
                            stringResource(R.string.settings_usage_access_needed)
                        },
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
                        title = stringResource(R.string.settings_notifications),
                        subtitle = if (hasNotificationPermission) {
                            stringResource(R.string.settings_notifications_active)
                        } else {
                            stringResource(R.string.settings_notifications_needed)
                        },
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

            SectionLabel(stringResource(R.string.settings_section_background), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("autostart_boot_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ---- the user's own pause switch ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        (if (meterPausedByUser) AccentWarning else LiveGreen).copy(alpha = 0.1f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AppIcon(
                                    resId = if (meterPausedByUser) R.drawable.ic_play else R.drawable.ic_pause,
                                    contentDescription = null,
                                    tint = if (meterPausedByUser) AccentWarning else LiveGreen,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_pause_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (meterPausedByUser) {
                                        stringResource(R.string.settings_pause_hint_paused)
                                    } else {
                                        stringResource(R.string.settings_pause_hint_running)
                                    },
                                    fontSize = 11.sp,
                                    color = secondary()
                                )
                            }
                        }
                        Switch(
                            checked = meterPausedByUser,
                            onCheckedChange = { viewModel.setMeterPaused(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = AccentWarning,
                                uncheckedThumbColor = MaterialTheme.colorScheme.primary,
                                uncheckedTrackColor = glassColor
                            ),
                            modifier = Modifier
                                .semantics {
                                    stateDescription = if (meterPausedByUser) pausedStateText else runningStateText
                                }
                                .testTag("meter_pause_switch")
                        )
                    }

                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))

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
                                text = stringResource(R.string.settings_autostart_title),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.settings_autostart_hint),
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
                            border = BorderStroke(1.dp, glassBorder),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .testTag("configure_autostart_button")
                        ) {
                            Text(stringResource(R.string.settings_autostart_button), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { openBatteryOptimizationSettings(context) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ),
                            border = BorderStroke(1.dp, glassBorder),
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
                            Text(stringResource(R.string.settings_battery_button), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------- DATA MANAGEMENT ----------------
            SectionLabel(stringResource(R.string.settings_section_data), top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("data_management_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DataRow(
                        iconRes = R.drawable.ic_restore,
                        title = stringResource(R.string.reset_today_title),
                        subtitle = stringResource(R.string.settings_reset_today_hint),
                        buttonLabel = stringResource(R.string.action_reset),
                        danger = false,
                        onButton = { showResetConfirmation = true },
                        tag = "reset_today_button"
                    )
                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                    DataRow(
                        iconRes = R.drawable.ic_delete_forever,
                        title = stringResource(R.string.purge_history_title),
                        subtitle = stringResource(R.string.settings_purge_hint),
                        buttonLabel = stringResource(R.string.action_purge),
                        danger = true,
                        onButton = { showPurgeConfirmation = true },
                        tag = "purge_history_button"
                    )
                }
            }

            // ---------------- SPEED TEST SERVER (collapsible) ----------------
            SectionLabel(stringResource(R.string.settings_section_server), top = 18.dp)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("test_server_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    CollapsibleHeader(
                        title = stringResource(R.string.settings_server_title),
                        summary = savedServerLabel(storedTestServer, storedTestServerUrl),
                        summaryTint = liveAccent,
                        summaryTag = "saved_server_label",
                        expanded = serverCardExpanded,
                        onToggle = { serverCardExpanded = !serverCardExpanded },
                        testTag = "test_server_header"
                    )
                    AnimatedVisibility(
                        visible = serverCardExpanded,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_server_hint,
                                    SpeedTestServers.CLOUDFLARE.host,
                                    SpeedTestServers.LIBRESPEED.host
                                ),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                            // Tapping an option is the save: no second "SAVE" step to forget.
                            ServerOptionRow(
                                title = stringResource(R.string.settings_server_auto),
                                subtitle = stringResource(R.string.settings_server_auto_hint),
                                selected = storedTestServer == SpeedTestServers.ID_AUTO,
                                onClick = {
                                    viewModel.updateTestServer(SpeedTestServers.ID_AUTO, customServerUrl, storedTestServerFlavour)
                                },
                                testTag = "server_option_auto"
                            )
                            ServerOptionRow(
                                title = SpeedTestServers.CLOUDFLARE.host,
                                subtitle = stringResource(R.string.settings_server_cloudflare_hint),
                                selected = storedTestServer == SpeedTestServers.ID_CLOUDFLARE,
                                onClick = {
                                    viewModel.updateTestServer(SpeedTestServers.ID_CLOUDFLARE, customServerUrl, storedTestServerFlavour)
                                },
                                testTag = "server_option_cloudflare"
                            )
                            ServerOptionRow(
                                title = SpeedTestServers.LIBRESPEED.host,
                                subtitle = stringResource(R.string.settings_server_librespeed_hint),
                                selected = storedTestServer == SpeedTestServers.ID_LIBRESPEED,
                                onClick = {
                                    viewModel.updateTestServer(SpeedTestServers.ID_LIBRESPEED, customServerUrl, storedTestServerFlavour)
                                },
                                testTag = "server_option_librespeed"
                            )
                            ServerOptionRow(
                                title = stringResource(R.string.settings_server_custom),
                                subtitle = stringResource(R.string.settings_server_custom_hint),
                                selected = storedTestServer == SpeedTestServers.ID_CUSTOM,
                                onClick = {
                                    viewModel.updateTestServer(SpeedTestServers.ID_CUSTOM, customServerUrl, storedTestServerFlavour)
                                },
                                testTag = "server_option_custom"
                            )

                            if (storedTestServer == SpeedTestServers.ID_CUSTOM) {
                                HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                                OutlinedTextField(
                                    value = customServerUrl,
                                    onValueChange = { customServerUrl = it.trim() },
                                    placeholder = { Text("https://myserver.example") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = glassBorder,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("custom_server_url_input")
                                )
                                ServerOptionRow(
                                    title = stringResource(R.string.settings_flavour_cloudflare),
                                    subtitle = "/__down?bytes=N · /__up",
                                    selected = storedTestServerFlavour == SpeedTestServers.FLAVOUR_CLOUDFLARE,
                                    onClick = {
                                        viewModel.updateTestServer(
                                            SpeedTestServers.ID_CUSTOM, customServerUrl, SpeedTestServers.FLAVOUR_CLOUDFLARE
                                        )
                                    },
                                    testTag = "server_flavour_cloudflare"
                                )
                                ServerOptionRow(
                                    title = stringResource(R.string.settings_flavour_librespeed),
                                    subtitle = "backend/garbage.php · backend/empty.php",
                                    selected = storedTestServerFlavour == SpeedTestServers.FLAVOUR_LIBRESPEED,
                                    onClick = {
                                        viewModel.updateTestServer(
                                            SpeedTestServers.ID_CUSTOM, customServerUrl, SpeedTestServers.FLAVOUR_LIBRESPEED
                                        )
                                    },
                                    testTag = "server_flavour_librespeed"
                                )
                                val resolved = SpeedTestServers.custom(customServerUrl, storedTestServerFlavour)
                                val unsaved = customServerUrl != storedTestServerUrl
                                Text(
                                    text = when {
                                        resolved == null -> stringResource(R.string.settings_server_custom_invalid)
                                        unsaved -> stringResource(R.string.settings_server_custom_unsaved, resolved.host)
                                        else -> stringResource(R.string.settings_server_custom_resolved, resolved.host)
                                    },
                                    fontSize = 10.sp,
                                    color = if (resolved == null || unsaved) AccentWarning else secondary(),
                                    modifier = Modifier.testTag("custom_server_status")
                                )
                                Button(
                                    onClick = {
                                        viewModel.updateTestServer(
                                            SpeedTestServers.ID_CUSTOM,
                                            customServerUrl,
                                            storedTestServerFlavour
                                        )
                                    },
                                    enabled = unsaved,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .testTag("save_custom_server_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        if (unsaved) {
                                            stringResource(R.string.settings_save_endpoint)
                                        } else {
                                            stringResource(R.string.settings_endpoint_saved)
                                        },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- MEASUREMENT ENGINE (collapsible) ----------------
            SectionLabel(stringResource(R.string.settings_section_engine), top = 18.dp)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("engine_diagnostics_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val statusText = when {
                        !serviceRunning && meterPausedByUser -> stringResource(R.string.engine_status_paused_by_user)
                        !serviceRunning -> stringResource(R.string.engine_status_stopped)
                        liveSpeed.paused -> stringResource(R.string.engine_status_paused)
                        else -> stringResource(R.string.engine_status_running)
                    }
                    CollapsibleHeader(
                        title = stringResource(R.string.settings_engine_title),
                        summary = "$statusText · ${engineStatus.source.label} · ${liveSpeed.windowMs} ms",
                        summaryTint = if (serviceRunning && !liveSpeed.paused) liveAccent else AccentWarning,
                        summaryTag = "engine_summary_label",
                        expanded = engineCardExpanded,
                        onToggle = { engineCardExpanded = !engineCardExpanded },
                        testTag = "engine_diagnostics_header"
                    )
                    AnimatedVisibility(
                        visible = engineCardExpanded,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(220)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EngineRow(stringResource(R.string.engine_row_status), statusText)
                            EngineRow(stringResource(R.string.engine_row_source), engineStatus.source.label)
                            EngineRow(
                                stringResource(R.string.engine_row_cadence),
                                stringResource(R.string.engine_row_cadence_value, liveSpeed.windowMs)
                            )
                            EngineRow(stringResource(R.string.engine_row_samples), liveSpeed.samplesTaken.toString())
                            EngineRow(stringResource(R.string.engine_row_window), "${liveSpeed.windowMs} ms")
                            EngineRow(stringResource(R.string.engine_row_network), networkTypeLabel(networkType))
                            EngineRow(
                                stringResource(R.string.engine_row_raw),
                                "rx ${BytesFormatter.format(liveSpeed.sessionRxBytes)} · tx ${BytesFormatter.format(liveSpeed.sessionTxBytes)}"
                            )
                            if (engineStatus.interfaces.isNotEmpty()) {
                                HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                                Text(
                                    text = stringResource(R.string.engine_interfaces_title),
                                    fontSize = 10.sp,
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
                                text = stringResource(R.string.engine_footnote),
                                fontSize = 10.sp,
                                color = secondary().copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // ---------------- ABOUT ----------------
            SectionLabel(stringResource(R.string.settings_section_about), top = 18.dp)
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
                            text = stringResource(R.string.app_name),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.settings_about_version, appVersion(context)),
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
            title = { Text(stringResource(R.string.reset_today_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.reset_today_body), color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTodayUsage()
                    showResetConfirmation = false
                }) { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text(stringResource(R.string.action_cancel), color = secondary()) }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showPurgeConfirmation) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmation = false },
            title = { Text(stringResource(R.string.purge_history_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.purge_history_body), color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllHistory()
                    showPurgeConfirmation = false
                }) { Text(stringResource(R.string.action_purge_all), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirmation = false }) { Text(stringResource(R.string.action_cancel), color = secondary()) }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

/** "Saved: …" — a human description of the stored endpoint choice, never a raw id. */
@Composable
private fun savedServerLabel(storedTestServer: String, storedUrl: String): String = when (storedTestServer) {
    SpeedTestServers.ID_CLOUDFLARE -> stringResource(R.string.settings_server_saved, SpeedTestServers.CLOUDFLARE.host)
    SpeedTestServers.ID_LIBRESPEED -> stringResource(R.string.settings_server_saved, SpeedTestServers.LIBRESPEED.host)
    SpeedTestServers.ID_CUSTOM -> stringResource(
        R.string.settings_server_saved_custom,
        storedUrl.ifBlank { stringResource(R.string.settings_server_no_url) }
    )
    else -> stringResource(
        R.string.settings_server_saved_auto,
        SpeedTestServers.CLOUDFLARE.host,
        SpeedTestServers.LIBRESPEED.host
    )
}

/**
 * Header of a collapsible group: title + one-line summary of the current state, and a
 * chevron that rotates. The whole row toggles, and it is exposed as a button.
 */
@Composable
private fun CollapsibleHeader(
    title: String,
    summary: String,
    summaryTint: Color,
    summaryTag: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    testTag: String
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "${testTag}_chevron"
    )
    val toggleLabel = if (expanded) stringResource(R.string.a11y_collapse) else stringResource(R.string.a11y_expand)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
            .padding(vertical = 2.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = summary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = summaryTint,
                modifier = Modifier.testTag(summaryTag)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(glassColor)
                .border(1.dp, glassBorder, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(
                resId = R.drawable.ic_expand_more,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )
        }
    }
}

/**
 * One selectable endpoint option: a small ring plus title/subtitle. Used instead of a
 * segmented control because the labels are hosts, not two-letter units. Tapping it
 * **saves** the choice (the caller passes the save as [onClick]).
 */
@Composable
private fun ServerOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(100.dp))
                .border(
                    width = if (selected) 5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else glassBorder,
                    shape = RoundedCornerShape(100.dp)
                )
        )
        Column {
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(text = subtitle, fontSize = 10.sp, color = secondary())
        }
    }
}

@Composable
private fun AccentPicker(selected: String, onSelected: (String) -> Unit) {
    val isDark = isDarkModeActive()
    // "INK" is the app's own primary; the swatch must not follow the *current* accent.
    val inkPrimary = if (isDark) DarkPrimary else LightPrimary
    // Material You swatch — only where the OS can supply a wallpaper colour (API 31+).
    val wallpaperPrimary = dynamicPrimary(isDark)?.first
    val accents = buildList {
        add("INK" to inkPrimary)
        add("GREEN" to (if (isDark) SpeedDownDark else SpeedDown))
        add("BLUE" to (if (isDark) SpeedUpDark else SpeedUp))
        add("VIOLET" to (if (isDark) AccentVioletDark else AccentViolet))
        if (wallpaperPrimary != null) add(AccentTheme.SYSTEM.key to wallpaperPrimary)
    }
    val systemLabel = stringResource(R.string.a11y_accent_system)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        accents.forEach { (key, color) ->
            val isSelected = key == selected
            val description = if (key == AccentTheme.SYSTEM.key) systemLabel else stringResource(R.string.a11y_accent, key)
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
                    .clickable(role = Role.RadioButton) { onSelected(key) }
                    .semantics {
                        contentDescription = description
                        this.selected = isSelected
                    }
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
    SpeedMeterService.NETWORK_WIFI -> stringResource(R.string.network_wifi)
    SpeedMeterService.NETWORK_MOBILE -> stringResource(R.string.network_mobile)
    SpeedMeterService.NETWORK_ETHERNET -> stringResource(R.string.network_ethernet)
    SpeedMeterService.NETWORK_VPN -> stringResource(R.string.network_vpn)
    else -> stringResource(R.string.network_offline)
}

@Composable
private fun SectionLabel(text: String, top: Dp = 12.dp) {
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
                text = stringResource(R.string.settings_permission_active),
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
            border = if (!danger) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)) else null,
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
                    .clickable(role = Role.RadioButton) { onSelected(option) }
                    .semantics { this.selected = isSelected }
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
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
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
