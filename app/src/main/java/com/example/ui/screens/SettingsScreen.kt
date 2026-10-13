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
import com.example.R
import com.example.services.SpeedMeterService
import com.example.services.SpeedTestServers
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
    val liveSpeed by SpeedMeterService.liveSpeedFlow.collectAsState()
    val engineStatus by SpeedMeterService.engineStatusFlow.collectAsState()
    val serviceRunning by SpeedMeterService.isServiceRunning.collectAsState()
    // Collected (not read once with `.value`), otherwise this row never refreshes.
    val networkType by SpeedMeterService.networkTypeFlow.collectAsState()
    val storedTestServer by viewModel.testServer.collectAsState()
    val storedTestServerUrl by viewModel.testServerUrl.collectAsState()
    val storedTestServerFlavour by viewModel.testServerFlavour.collectAsState()

    var mobileLimitText by remember { mutableStateOf("") }
    var mobileLimitUnit by remember { mutableStateOf("GB") }
    var isLimitInitialized by remember { mutableStateOf(false) }

    var serverPreference by remember { mutableStateOf(SpeedTestServers.ID_AUTO) }
    var customServerUrl by remember { mutableStateOf("") }
    var customServerFlavour by remember { mutableStateOf(SpeedTestServers.FLAVOUR_CLOUDFLARE) }
    var isServerInitialized by remember { mutableStateOf(false) }

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

    LaunchedEffect(storedTestServer, storedTestServerUrl, storedTestServerFlavour) {
        if (!isServerInitialized) {
            serverPreference = storedTestServer
            customServerUrl = storedTestServerUrl
            customServerFlavour = storedTestServerFlavour
            isServerInitialized = true
        }
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
                    text = "SYSTEM SETTINGS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondary()
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "App Preferences",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // ---------------- APPEARANCE ----------------
            SectionLabel("APPEARANCE")
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
                                    contentDescription = "Theme",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Dark Mode",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = when {
                                        isDark == null -> "Following system"
                                        isDark == true -> "Always on"
                                        else -> "Always off"
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
                                text = "Speed Unit",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (speedUnit == SpeedUnit.BYTES)
                                    "KB/s · MB/s — device meter style"
                                else
                                    "Kbps · Mbps — the unit ISPs advertise",
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
                                text = "Accent",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Tints the buttons and the selected nav pill.",
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
            SectionLabel("DAILY MOBILE DATA ALERTS", top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("limits_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Daily Mobile Data Limit",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Get a prominent on-screen warning at 50%, 90% and 100%.",
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
                            placeholder = { Text("e.g. 2") },
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
                        Text("Save Limit", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ---------------- PERMISSIONS ----------------
            SectionLabel("SYSTEM PERMISSIONS", top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("permissions_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PermissionRow(
                        iconRes = R.drawable.ic_shield,
                        title = "Usage Access",
                        subtitle = if (hasUsagePermission) "Exact system counters active"
                        else "Needed for exact daily totals",
                        active = hasUsagePermission,
                        liveAccent = liveAccent,
                        buttonLabel = "GRANT",
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
                        title = "Notifications",
                        subtitle = if (hasNotificationPermission) "Status-bar ticker visible"
                        else "Shows the live speed ticker",
                        active = hasNotificationPermission,
                        liveAccent = liveAccent,
                        buttonLabel = "GRANT",
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

            SectionLabel("KEEP RUNNING IN BACKGROUND", top = 18.dp)
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
                                text = "Auto Start & Battery",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Some brands kill background apps. Allow both to keep the meter alive 24/7.",
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
                            Text("Autostart", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                            Text("Battery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------- DATA MANAGEMENT ----------------
            SectionLabel("DATA MANAGEMENT", top = 18.dp)
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("data_management_card")) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DataRow(
                        iconRes = R.drawable.ic_restore,
                        title = "Reset Today's Usage",
                        subtitle = "Zero out today's counters.",
                        buttonLabel = "RESET",
                        danger = false,
                        onButton = { showResetConfirmation = true },
                        tag = "reset_today_button"
                    )
                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                    DataRow(
                        iconRes = R.drawable.ic_delete_forever,
                        title = "Purge History",
                        subtitle = "Delete all stored history permanently.",
                        buttonLabel = "PURGE",
                        danger = true,
                        onButton = { showPurgeConfirmation = true },
                        tag = "purge_history_button"
                    )
                }
            }

            // ---------------- SPEED TEST SERVER ----------------
            SectionLabel("SPEED TEST SERVER", top = 18.dp)
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .testTag("test_server_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Where the opt-in test measures",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Some networks and corporate DNS servers block one host. AUTO tries " +
                            "${SpeedTestServers.CLOUDFLARE.host} first and falls back to " +
                            "${SpeedTestServers.LIBRESPEED.host} when it cannot be reached; the test " +
                            "always shows which host the numbers came from.",
                        fontSize = 11.sp,
                        color = secondary()
                    )
                    Text(
                        text = buildString {
                            append("Saved: ")
                            append(storedTestServer)
                            if (storedTestServer == SpeedTestServers.ID_CUSTOM) {
                                append(" · ")
                                append(storedTestServerUrl.ifBlank { "no URL yet" })
                            }
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = liveAccent,
                        modifier = Modifier.testTag("saved_server_label")
                    )
                    ServerOptionRow(
                        title = "AUTO",
                        subtitle = "Cloudflare, with LibreSpeed as fallback",
                        selected = serverPreference == SpeedTestServers.ID_AUTO,
                        onClick = { serverPreference = SpeedTestServers.ID_AUTO },
                        testTag = "server_option_auto"
                    )
                    ServerOptionRow(
                        title = SpeedTestServers.CLOUDFLARE.host,
                        subtitle = "Cloudflare only",
                        selected = serverPreference == SpeedTestServers.ID_CLOUDFLARE,
                        onClick = { serverPreference = SpeedTestServers.ID_CLOUDFLARE },
                        testTag = "server_option_cloudflare"
                    )
                    ServerOptionRow(
                        title = SpeedTestServers.LIBRESPEED.host,
                        subtitle = "LibreSpeed public backend only",
                        selected = serverPreference == SpeedTestServers.ID_LIBRESPEED,
                        onClick = { serverPreference = SpeedTestServers.ID_LIBRESPEED },
                        testTag = "server_option_librespeed"
                    )
                    ServerOptionRow(
                        title = "Custom host",
                        subtitle = "Your own Cloudflare- or LibreSpeed-compatible endpoint",
                        selected = serverPreference == SpeedTestServers.ID_CUSTOM,
                        onClick = { serverPreference = SpeedTestServers.ID_CUSTOM },
                        testTag = "server_option_custom"
                    )

                    if (serverPreference == SpeedTestServers.ID_CUSTOM) {
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
                            title = "Cloudflare-style",
                            subtitle = "/__down?bytes=N and /__up",
                            selected = customServerFlavour == SpeedTestServers.FLAVOUR_CLOUDFLARE,
                            onClick = { customServerFlavour = SpeedTestServers.FLAVOUR_CLOUDFLARE },
                            testTag = "server_flavour_cloudflare"
                        )
                        ServerOptionRow(
                            title = "LibreSpeed-style",
                            subtitle = "backend/garbage.php and backend/empty.php",
                            selected = customServerFlavour == SpeedTestServers.FLAVOUR_LIBRESPEED,
                            onClick = { customServerFlavour = SpeedTestServers.FLAVOUR_LIBRESPEED },
                            testTag = "server_flavour_librespeed"
                        )
                        val resolved = SpeedTestServers.custom(customServerUrl, customServerFlavour)
                        Text(
                            text = if (resolved == null) {
                                "Enter a full http(s) URL — until then the default Cloudflare host is used."
                            } else {
                                "Test will talk to ${resolved.host}"
                            },
                            fontSize = 10.sp,
                            color = if (resolved == null) AccentWarning else secondary()
                        )
                        Button(
                            onClick = {
                                viewModel.updateTestServer(
                                    SpeedTestServers.ID_CUSTOM,
                                    customServerUrl,
                                    customServerFlavour
                                )
                            },
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
                            Text("SAVE ENDPOINT", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (serverPreference != SpeedTestServers.ID_CUSTOM) {
                        Button(
                            onClick = {
                                viewModel.updateTestServer(
                                    serverPreference,
                                    customServerUrl,
                                    customServerFlavour
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .testTag("save_server_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("SAVE SERVER", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------- MEASUREMENT ENGINE ----------------
            SectionLabel("MEASUREMENT ENGINE", top = 18.dp)
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
                        text = "Live, straight from the OS counters",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    EngineRow("Status", if (serviceRunning) (if (liveSpeed.paused) "Running (paused)" else "Running") else "Stopped")
                    EngineRow("Counter source", engineStatus.source.label)
                    EngineRow("Read cadence", "500 ms · publish ${liveSpeed.windowMs} ms window")
                    EngineRow("Samples this session", liveSpeed.samplesTaken.toString())
                    EngineRow("Last published window", "${liveSpeed.windowMs} ms")
                    EngineRow("Network", networkTypeLabel(networkType))
                    EngineRow(
                        "Raw counters",
                        "rx ${BytesFormatter.format(liveSpeed.sessionRxBytes)} · tx ${BytesFormatter.format(liveSpeed.sessionTxBytes)}"
                    )
                    if (engineStatus.interfaces.isNotEmpty()) {
                        HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                        Text(
                            text = "KERNEL INTERFACES (/proc/net/dev)",
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
                        text = "These are the raw numbers the meter is built on, so you can verify " +
                            "every value yourself. Nothing here is estimated or simulated.",
                        fontSize = 10.sp,
                        color = secondary().copy(alpha = 0.85f)
                    )
                }
            }

            // ---------------- ABOUT ----------------
            SectionLabel("ABOUT", top = 18.dp)
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
                            text = "Internet Speed Meter",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Version ${appVersion(context)} · real-time engine",
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
            title = { Text("Reset Today's Usage", fontWeight = FontWeight.Bold) },
            text = { Text("Today's counters will be set back to zero. This cannot be undone.", color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTodayUsage()
                    showResetConfirmation = false
                }) { Text("RESET", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) { Text("CANCEL", color = secondary()) }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showPurgeConfirmation) {
        AlertDialog(
            onDismissRequest = { showPurgeConfirmation = false },
            title = { Text("Purge History", fontWeight = FontWeight.Bold) },
            text = { Text("All stored history will be permanently deleted. This is irreversible.", color = secondary()) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllHistory()
                    showPurgeConfirmation = false
                }) { Text("PURGE ALL", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPurgeConfirmation = false }) { Text("CANCEL", color = secondary()) }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

/**
 * One selectable endpoint option: a small ring plus title/subtitle. Used instead of a
 * segmented control because the labels are hosts, not two-letter units.
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
            .clickable(onClick = onClick)
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

private fun networkTypeLabel(type: String): String = when (type) {
    SpeedMeterService.NETWORK_WIFI -> "Wi-Fi"
    SpeedMeterService.NETWORK_MOBILE -> "Mobile data"
    SpeedMeterService.NETWORK_ETHERNET -> "Ethernet"
    SpeedMeterService.NETWORK_VPN -> "VPN"
    else -> "Offline"
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
                text = "ACTIVE",
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
