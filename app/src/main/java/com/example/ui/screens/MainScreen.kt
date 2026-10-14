package com.example.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DataUsageRepository
import com.example.data.PreferencesManager
import com.example.services.ServiceScheduler
import com.example.ui.components.AppIcon
import com.example.ui.theme.glassBorder
import com.example.ui.theme.glassColor
import com.example.ui.util.secondary
import com.example.util.DateFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Speed")
    object SpeedTest : Screen("test", "Test")
    object History : Screen("history", "History")
    object Settings : Screen("settings", "Settings")
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val database = remember { AppDatabase.getDatabase(context) }
    val repository = remember { DataUsageRepository(database.dataUsageDao()) }
    val preferencesManager = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()

    // Keep the meter alive (Android 14/15 background-start restrictions).
    LaunchedEffect(Unit) {
        ServiceScheduler.schedule(context)
    }

    // Track "today" so midnight rollovers refresh the alert logic.
    var todayString by remember {
        mutableStateOf(DateFormatter.day())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            todayString = DateFormatter.day()
        }
    }

    val todayEntity by repository.getDataByDate(todayString).collectAsState(initial = null)

    val mobileLimit by preferencesManager.mobileLimitFlow.collectAsState(initial = 0.0)
    val mobileLimitUnit by preferencesManager.mobileLimitUnitFlow.collectAsState(initial = "GB")
    val lastDismissedMobileAlert by preferencesManager.lastDismissedMobileAlertFlow.collectAsState(initial = 0L)
    val lastDismissedAlertDate by preferencesManager.lastDismissedAlertDateFlow.collectAsState(initial = "")

    val effectiveDismissedMobile = if (lastDismissedAlertDate == todayString) lastDismissedMobileAlert else 0L
    val mobileUsed = todayEntity?.mobileTotal ?: 0L

    val factorMobile =
        if (mobileLimitUnit.uppercase(Locale.ROOT) == "GB") 1024L * 1024L * 1024L else 1024L * 1024L
    val mobileLimitBytes = (mobileLimit * factorMobile).toLong()

    // Which warning level (if any) is due right now. Kept as a `val` on purpose: a `val`
    // keeps its non-null smart cast inside the dialog lambdas, a captured `var` does not.
    val pendingMobileAlert: Long? = if (mobileLimitBytes > 0) {
        val pct = (mobileUsed.toDouble() / mobileLimitBytes.toDouble()) * 100.0
        val targetAlertLevel = when {
            pct >= 100.0 -> 100L
            pct >= 90.0 -> 90L
            pct >= 50.0 -> 50L
            else -> 0L
        }
        if (targetAlertLevel > effectiveDismissedMobile) targetAlertLevel else null
    } else {
        null
    }

    LaunchedEffect(pendingMobileAlert) {
        val level = pendingMobileAlert ?: return@LaunchedEffect
        vibrateLimitAlert(context, level)
    }

    // `level` is a non-null lambda parameter, so the dialog lambdas need no smart cast.
    pendingMobileAlert?.let { level ->
        AlertDialog(
            // Swiping it away counts as acknowledgement: the warning is repeated at most
            // once per threshold per day, and the app must never trap the user in a modal.
            onDismissRequest = {
                scope.launch {
                    preferencesManager.saveDismissedMobileAlert(level, todayString)
                }
            },
            title = {
                Text(
                    text = if (level >= 100L) {
                        stringResource(R.string.limit_alert_title_reached)
                    } else {
                        stringResource(R.string.limit_alert_title_warning)
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.limit_alert_body,
                        level,
                        formatBytesWarning(mobileUsed),
                        formatLimitValue(mobileLimit),
                        mobileLimitUnit
                    ),
                    color = secondary()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            preferencesManager.saveDismissedMobileAlert(level, todayString)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_screen_scaffold"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // full-bleed, glass nav floats above insets
        bottomBar = {
            FloatingGlassNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .testTag("screen_content_host")
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Dashboard.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Dashboard.route) { DashboardScreen() }
                composable(Screen.SpeedTest.route) { SpeedTestScreen() }
                composable(Screen.History.route) { HistoryScreen() }
                composable(Screen.Settings.route) { SettingsScreen() }
            }
        }
    }
}

/**
 * Premium floating "pill" navigation: a frosted-glass bar hovering above the
 * home indicator, selected item highlighted with an ink pill.
 */
@Composable
private fun FloatingGlassNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit
) {
    // Labels come from resources so the navigation is localizable (values-hi included).
    val items = listOf(
        Triple(Screen.Dashboard, R.drawable.ic_speed, stringResource(R.string.nav_speed)),
        Triple(Screen.SpeedTest, R.drawable.ic_bolt, stringResource(R.string.nav_test)),
        Triple(Screen.History, R.drawable.ic_history, stringResource(R.string.nav_history)),
        Triple(Screen.Settings, R.drawable.ic_settings, stringResource(R.string.nav_settings))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp, top = 8.dp)
            .testTag("glassy_bottom_nav_container")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = Color.Black.copy(alpha = 0.10f),
                    spotColor = Color.Black.copy(alpha = 0.12f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(glassColor)
                .border(1.dp, glassBorder, RoundedCornerShape(32.dp))
                .padding(6.dp)
                .testTag("bottom_nav_bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (screen, iconRes, label) ->
                val isSelected = currentRoute == screen.route
                val pillWidth by animateDpAsState(
                    targetValue = if (isSelected) 72.dp else 56.dp,
                    animationSpec = tween(durationMillis = 260),
                    label = "nav_pill_${screen.route}"
                )
                Box(
                    modifier = Modifier
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(pillWidth)
                            .height(46.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onNavigate(screen) }
                            .testTag("nav_item_${screen.route}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(
                                resId = iconRes,
                                contentDescription = label,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.size(19.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                                modifier = Modifier.testTag("nav_label_${screen.route}")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A limit alert must be felt, not only seen: one short buzz at 50/90 %, a double buzz at
 * 100 %. This is the *only* vibration in the app and it needs no permission prompt.
 */
private fun vibrateLimitAlert(context: Context, level: Long) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = if (level >= 100L) longArrayOf(0L, 140L, 90L, 140L) else longArrayOf(0L, 70L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    } catch (e: Exception) {
        // Vibration is a nicety: never let it break the alert dialog.
        e.printStackTrace()
    }
}

/** "2" for 2.0, "1.5" for 1.5 — the limit as the user typed it, not as a Double prints. */
private fun formatLimitValue(limit: Double): String =
    if (limit % 1.0 == 0.0) limit.toLong().toString() else limit.toString()

private fun formatBytesWarning(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}
