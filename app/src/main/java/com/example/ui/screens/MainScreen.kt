package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.AppDatabase
import com.example.data.DataUsageRepository
import com.example.data.PreferencesManager
import com.example.ui.theme.borderColor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Dashboard")
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

    var todayString by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000)
            todayString = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }
    }
    val todayEntity by repository.getDataByDate(todayString).collectAsState(initial = null)

    val mobileLimit by preferencesManager.mobileLimitFlow.collectAsState(initial = 2.0)
    val mobileLimitUnit by preferencesManager.mobileLimitUnitFlow.collectAsState(initial = "GB")
    val addonLimit by preferencesManager.addonLimitFlow.collectAsState(initial = 500.0)
    val addonLimitUnit by preferencesManager.addonLimitUnitFlow.collectAsState(initial = "MB")
    val isAddOnActive by preferencesManager.isAddOnActiveFlow.collectAsState(initial = false)

    val lastDismissedMobileAlert by preferencesManager.lastDismissedMobileAlertFlow.collectAsState(initial = 0L)
    val lastDismissedAddonAlert by preferencesManager.lastDismissedAddonAlertFlow.collectAsState(initial = 0L)
    val lastDismissedAlertDate by preferencesManager.lastDismissedAlertDateFlow.collectAsState(initial = "")

    val effectiveDismissedMobile = if (lastDismissedAlertDate == todayString) lastDismissedMobileAlert else 0L
    val effectiveDismissedAddon = if (lastDismissedAlertDate == todayString) lastDismissedAddonAlert else 0L

    val mobileUsed = todayEntity?.mobileDataUsed ?: 0L
    val addonUsed = todayEntity?.addOnDataUsed ?: 0L

    val factorMobile = if (mobileLimitUnit.uppercase(Locale.ROOT) == "GB") 1024L * 1024L * 1024L else 1024L * 1024L
    val mobileLimitBytes = (mobileLimit * factorMobile).toLong()

    val factorAddon = if (addonLimitUnit.uppercase(Locale.ROOT) == "GB") 1024L * 1024L * 1024L else 1024L * 1024L
    val addonLimitBytes = (addonLimit * factorAddon).toLong()

    // Determine pending Mobile alerts
    var pendingMobileAlert: Long? = null
    if (mobileLimitBytes > 0) {
        val pct = (mobileUsed.toDouble() / mobileLimitBytes.toDouble()) * 100.0
        val targetAlertLevel = when {
            pct >= 100.0 -> 100L
            pct >= 90.0 -> 90L
            pct >= 50.0 -> 50L
            else -> 0L
        }
        if (targetAlertLevel > effectiveDismissedMobile) {
            pendingMobileAlert = targetAlertLevel
        }
    }

    // Determine pending AddOn alerts
    var pendingAddonAlert: Long? = null
    if (addonLimitBytes > 0 && isAddOnActive) {
        val pct = (addonUsed.toDouble() / addonLimitBytes.toDouble()) * 100.0
        val targetAlertLevel = when {
            pct >= 100.0 -> 100L
            pct >= 90.0 -> 90L
            pct >= 50.0 -> 50L
            else -> 0L
        }
        if (targetAlertLevel > effectiveDismissedAddon) {
            pendingAddonAlert = targetAlertLevel
        }
    }

    if (pendingMobileAlert != null) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    text = if (pendingMobileAlert >= 100L) "Daily Mobile Limit Exceeded!" else "Daily Mobile Data Warning",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Alert: You have crossed ${pendingMobileAlert}% of your daily mobile data limit!\n\n" +
                           "Current usage: ${formatBytesWarning(mobileUsed)} of ${mobileLimit} ${mobileLimitUnit}."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            preferencesManager.saveDismissedAlerts(
                                mobileLevel = pendingMobileAlert,
                                addonLevel = effectiveDismissedAddon,
                                date = todayString
                            )
                        }
                    }
                ) {
                    Text("OK")
                }
            }
        )
    } else if (pendingAddonAlert != null) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible */ },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(
                    text = if (pendingAddonAlert >= 100L) "Add-On Limit Exceeded!" else "Add-On Custom Data Warning",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Alert: You have crossed ${pendingAddonAlert}% of your custom add-on data limit!\n\n" +
                           "Current usage: ${formatBytesWarning(addonUsed)} of ${addonLimit} ${addonLimitUnit}."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            preferencesManager.saveDismissedAlerts(
                                mobileLevel = effectiveDismissedMobile,
                                addonLevel = pendingAddonAlert,
                                date = todayString
                            )
                        }
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    val topBorderColor = MaterialTheme.borderColor

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_screen_scaffold"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // Full bleed content under navbar
        bottomBar = {
            // Elegant glassy/translucent bottom bar container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .drawBehind {
                        // Sharp high-end technical borderline on top of navigation
                        drawLine(
                            color = topBorderColor,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("glassy_bottom_nav_container")
            ) {
                NavigationBar(
                    containerColor = Color.Transparent, // Guided by container Box background
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bottom_nav_bar")
                ) {
                    val items = listOf(
                        Triple(Screen.Dashboard, Icons.Filled.Speed, Icons.Outlined.Speed),
                        Triple(Screen.History, Icons.Filled.History, Icons.Outlined.History),
                        Triple(Screen.Settings, Icons.Filled.Settings, Icons.Outlined.Settings)
                    )

                    items.forEach { (screen, selectedIcon, unselectedIcon) ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        // Pop up to the start destination of the graph to
                                        // avoid building up a large stack of destinations
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        // Avoid multiple copies of the same destination when
                                        // reselecting the same item
                                        launchSingleTop = true
                                        // Restore state when reselecting a previously selected item
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) selectedIcon else unselectedIcon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.testTag("nav_icon_${screen.route}")
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontSize = 11.sp,
                                    modifier = Modifier.testTag("nav_label_${screen.route}")
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                indicatorColor = MaterialTheme.colorScheme.primary // Clean minimal pill color
                            ),
                            modifier = Modifier.testTag("nav_item_${screen.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        // Render current screen inside Scaffold content, with bottom safe padding matching the bottom bar
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
                composable(Screen.Dashboard.route) {
                    DashboardScreen()
                }
                composable(Screen.History.route) {
                    HistoryScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
            }
        }
    }
}

private fun formatBytesWarning(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}

