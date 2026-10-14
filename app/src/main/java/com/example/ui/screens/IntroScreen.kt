package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.ui.theme.motionDuration
import com.example.R
import com.example.ui.components.AppBackground
import com.example.ui.components.AppIcon
import com.example.ui.components.GlassCard
import com.example.ui.theme.AccentWarning
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.LiveGreenDark
import com.example.ui.theme.SpeedUp
import com.example.ui.theme.SpeedUpDark
import com.example.ui.theme.glassBorder
import com.example.ui.theme.glassColor
import com.example.ui.theme.isDarkModeActive
import com.example.ui.util.secondary

@Composable
fun IntroScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(0) }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var hasUsageStatsPermission by remember {
        mutableStateOf(checkUsageStatsPermissionLocal(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    fun refreshPermissions() {
        hasUsageStatsPermission = checkUsageStatsPermissionLocal(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage == 2) refreshPermissions()
    }

    // Usage Access is granted in the system Settings app; re-check when the user comes
    // back instead of waiting for a page change.
    LifecycleResumeEffect(Unit) {
        refreshPermissions()
        onPauseOrDispose { }
    }

    val pageCount = 4
    val isDark = isDarkModeActive()
    val liveColor = if (isDark) LiveGreenDark else LiveGreen

    Box(modifier = Modifier.fillMaxSize().testTag("intro_scaffold")) {
        AppBackground()

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // Icon tile
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(glassColor)
                    .border(1.dp, glassBorder, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    resId = when (currentPage) {
                        0 -> R.drawable.ic_speed
                        1 -> R.drawable.ic_data_usage
                        2 -> R.drawable.ic_shield
                        else -> R.drawable.ic_bolt
                    },
                    contentDescription = null,
                    tint = when (currentPage) {
                        0 -> if (isDark) LiveGreenDark else LiveGreen
                        1 -> if (isDark) SpeedUpDark else SpeedUp
                        else -> MaterialTheme.colorScheme.onBackground
                    },
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = when (currentPage) {
                    0 -> stringResource(R.string.intro_eyebrow_0)
                    1 -> stringResource(R.string.intro_eyebrow_1)
                    2 -> stringResource(R.string.intro_eyebrow_2)
                    else -> stringResource(R.string.intro_eyebrow_3)
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (currentPage) {
                    0 -> stringResource(R.string.intro_title_0)
                    1 -> stringResource(R.string.intro_title_1)
                    2 -> stringResource(R.string.intro_title_2)
                    else -> stringResource(R.string.intro_title_3)
                },
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (currentPage == 2) {
                PermissionsPage(
                    hasNotification = hasNotificationPermission,
                    hasUsage = hasUsageStatsPermission,
                    liveColor = liveColor,
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestUsage = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            } else {
                Text(
                    text = when (currentPage) {
                        0 -> stringResource(R.string.intro_body_0)
                        1 -> stringResource(R.string.intro_body_1)
                        else -> stringResource(R.string.intro_body_3)
                    },
                    fontSize = 13.5.sp,
                    color = secondary(),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 36.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ---------------- Bottom bar ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPage > 0) {
                    IconButton(
                        onClick = { currentPage-- },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(glassColor)
                            .border(1.dp, glassBorder, CircleShape)
                            .testTag("intro_back_button")
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_chevron_left,
                            contentDescription = stringResource(R.string.a11y_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(46.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pageCount) { index ->
                        val isSelected = index == currentPage
                        val dotWidth by animateDpAsState(
                            targetValue = if (isSelected) 22.dp else 8.dp,
                            animationSpec = tween(motionDuration(260)),
                            label = "intro_dot_$index"
                        )
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(dotWidth)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (currentPage < pageCount - 1) currentPage++ else onComplete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag(if (currentPage < pageCount - 1) "intro_next_button" else "intro_start_button")
                ) {
                    Text(
                        text = if (currentPage < pageCount - 1) {
                            stringResource(R.string.action_next)
                        } else {
                            stringResource(R.string.action_start)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    AppIcon(
                        resId = R.drawable.ic_chevron_right,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsPage(
    hasNotification: Boolean,
    hasUsage: Boolean,
    liveColor: Color,
    onRequestNotification: () -> Unit,
    onRequestUsage: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PermissionTile(
                iconRes = R.drawable.ic_notifications,
                title = stringResource(R.string.intro_perm_ticker_title),
                subtitle = if (hasNotification) {
                    stringResource(R.string.intro_perm_ticker_active)
                } else {
                    stringResource(R.string.intro_perm_ticker_hint)
                },
                active = hasNotification,
                liveColor = liveColor,
                onGrant = onRequestNotification
            )
            HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
            PermissionTile(
                iconRes = R.drawable.ic_shield,
                title = stringResource(R.string.usage_access_title),
                subtitle = if (hasUsage) {
                    stringResource(R.string.intro_perm_usage_active)
                } else {
                    stringResource(R.string.intro_perm_usage_hint)
                },
                active = hasUsage,
                liveColor = liveColor,
                onGrant = onRequestUsage
            )
        }
    }
}

@Composable
private fun PermissionTile(
    iconRes: Int,
    title: String,
    subtitle: String,
    active: Boolean,
    liveColor: Color,
    onGrant: () -> Unit
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
                    .background(
                        if (active) liveColor.copy(alpha = 0.12f) else AccentWarning.copy(alpha = 0.12f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    resId = iconRes,
                    contentDescription = null,
                    tint = if (active) liveColor else AccentWarning,
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
            AppIcon(
                resId = R.drawable.ic_check,
                contentDescription = stringResource(R.string.a11y_granted),
                tint = liveColor,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(stringResource(R.string.action_grant), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun checkUsageStatsPermissionLocal(context: android.content.Context): Boolean {
    val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.checkOpNoThrow(
        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}
