package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.PreferencesManager
import com.example.services.SpeedMeterService
import com.example.services.Transport
import com.example.ui.components.AppBackground
import com.example.ui.components.AppIcon
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassChip
import com.example.ui.theme.AccentDanger
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
import com.example.ui.util.dlColor
import com.example.ui.util.ulColor
import com.example.ui.util.secondary
import com.example.ui.util.toBytes
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.DataUsage
import com.example.util.BytesFormatter
import com.example.util.DateFormatter
import com.example.util.SpeedFormatter
import com.example.util.SpeedUnit
import kotlin.math.roundToLong

@Composable
private fun SpeedDownC(): Color = dlColor()

@Composable
private fun SpeedUpC(): Color = ulColor()

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel()
) {
    val mobileUsage by viewModel.mobileData.collectAsState()
    val wifiUsage by viewModel.wifiData.collectAsState()
    val totalUsage by viewModel.totalUsage.collectAsState()
    val limitProgress by viewModel.limitProgress.collectAsState()
    val limitLabel by viewModel.mobileLimitLabel.collectAsState()
    val showResetDialog by viewModel.showResetDialog.collectAsState()
    val permissionGranted by viewModel.permissionGranted.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen_root")
    ) {
        AppBackground()

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // A single column is right for a phone; from 600 dp (tablets, landscape,
        // foldables) the live card and the daily cards sit side by side like a native
        // dashboard instead of stretching to a 1000 dp wide column.
        val twoColumn = maxWidth >= 600.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- Header ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.dashboard_title),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = DateFormatter.shortDay(),
                        fontSize = 12.sp,
                        color = secondary()
                    )
                }
                IconButton(
                    onClick = { viewModel.requestResetTodayUsage() },
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(glassColor)
                        .border(1.dp, glassBorder, RoundedCornerShape(10.dp))
                        .size(38.dp)
                        .testTag("dashboard_reset_today")
                ) {
                    AppIcon(
                        resId = R.drawable.ic_restore,
                        contentDescription = stringResource(R.string.a11y_reset_today),
                        tint = AccentDanger,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (twoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ---------------- Hero: live speed ----------------
                        HeroSpeedCard()

                        // ---------------- Permission hint ----------------
                        if (!permissionGranted) {
                            UsageAccessHintCard()
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ---------------- Mobile / WiFi cards ----------------
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            UsageCard(
                                title = stringResource(R.string.usage_mobile_title),
                                iconRes = R.drawable.ic_cellular,
                                data = mobileUsage,
                                modifier = Modifier.weight(1f).testTag("mobile_data_card")
                            )
                            UsageCard(
                                title = stringResource(R.string.network_wifi),
                                iconRes = R.drawable.ic_wifi,
                                data = wifiUsage,
                                modifier = Modifier.weight(1f).testTag("wifi_data_card")
                            )
                        }

                        // ---------------- Total card ----------------
                        TotalCard(
                            data = totalUsage,
                            limitProgress = limitProgress,
                            limitLabel = limitLabel,
                            modifier = Modifier.testTag("total_data_card")
                        )
                    }
                }
            } else {
                // ---------------- Hero: live speed ----------------
                HeroSpeedCard()

                // ---------------- Permission hint ----------------
                if (!permissionGranted) {
                    UsageAccessHintCard()
                }

                // ---------------- Mobile / WiFi cards ----------------
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    UsageCard(
                        title = stringResource(R.string.usage_mobile_title),
                        iconRes = R.drawable.ic_cellular,
                        data = mobileUsage,
                        modifier = Modifier.weight(1f).testTag("mobile_data_card")
                    )
                    UsageCard(
                        title = stringResource(R.string.network_wifi),
                        iconRes = R.drawable.ic_wifi,
                        data = wifiUsage,
                        modifier = Modifier.weight(1f).testTag("wifi_data_card")
                    )
                }

                // ---------------- Total card ----------------
                TotalCard(
                    data = totalUsage,
                    limitProgress = limitProgress,
                    limitLabel = limitLabel,
                    modifier = Modifier.testTag("total_data_card")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
        }

        // ---------------- Reset confirmation dialog ----------------
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissResetDialog() },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmResetTodayUsage() }) {
                        Text(stringResource(R.string.action_reset), fontWeight = FontWeight.Bold, color = AccentDanger)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissResetDialog() }) {
                        Text(stringResource(R.string.action_cancel), color = secondary())
                    }
                },
                title = { Text(stringResource(R.string.reset_today_title), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.reset_today_body),
                        color = secondary()
                    )
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

// ============================== HERO SPEED CARD ==============================

/**
 * The live card. Everything shown here comes straight from [SpeedMeterService]'s engine:
 * raw measured rates (animated so the digits roll instead of jumping), the 60 s average,
 * the session total, per-network attribution and the session peak.
 */
@Composable
fun HeroSpeedCard(modifier: Modifier = Modifier) {
    val live by SpeedMeterService.liveSpeedFlow.collectAsState()
    val isRunning by SpeedMeterService.isServiceRunning.collectAsState()
    val context = LocalContext.current
    val preferencesManager = remember(context) { PreferencesManager(context) }
    val speedUnit by preferencesManager.speedUnitFlow.collectAsState(initial = SpeedUnit.BYTES)

    val isDark = isDarkModeActive()
    val downColor = if (isDark) SpeedDownDark else SpeedDown
    val upColor = if (isDark) SpeedUpDark else SpeedUp
    val liveColor = if (isDark) LiveGreenDark else LiveGreen

    // Digit roll: the displayed value chases the measured value instead of swapping.
    val animatedDownload by animateFloatAsState(
        targetValue = live.downloadBps.coerceAtLeast(0L).toFloat(),
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "hero_download_digits"
    )
    val animatedUpload by animateFloatAsState(
        targetValue = live.uploadBps.coerceAtLeast(0L).toFloat(),
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "hero_upload_digits"
    )

    val (dlValue, dlUnit) = SpeedFormatter.split(animatedDownload.roundToLong(), speedUnit)
    val (ulValue, ulUnit) = SpeedFormatter.split(animatedUpload.roundToLong(), speedUnit)

    GlassCard(modifier = modifier.fillMaxWidth().testTag("live_speed_card")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Status row: network chip + session peak chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassChip {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        AppIcon(
                            resId = networkIcon(live.transport),
                            contentDescription = stringResource(R.string.a11y_network_type),
                            tint = if (live.transport == Transport.NONE) secondary()
                            else MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = networkLabel(live.transport),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (isRunning && !live.paused) liveColor else Color(0xFFB8BEC8))
                        )
                    }
                }

                GlassChip {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_arrow_down,
                            contentDescription = null,
                            tint = downColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = stringResource(R.string.hero_peak, SpeedFormatter.format(live.peakDownloadBps, speedUnit)),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = secondary()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Big numbers: download + upload. The value nodes describe themselves with
            // their unit ("Download, 1.2 MB/s") so a screen reader never gets a bare digit.
            val downloadDescription = stringResource(R.string.a11y_live_download, "$dlValue $dlUnit")
            val uploadDescription = stringResource(R.string.a11y_live_upload, "$ulValue $ulUnit")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.15f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(downColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_arrow_down,
                                contentDescription = null,
                                tint = downColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.label_download),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            color = secondary()
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = dlValue,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .testTag("live_download_speed_text")
                                .semantics { contentDescription = downloadDescription }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = dlUnit,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = secondary()
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 14.dp)
                        .width(1.dp)
                        .height(48.dp)
                        .background(glassBorder)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(upColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_arrow_up,
                                contentDescription = null,
                                tint = upColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.label_upload),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            color = secondary()
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = ulValue,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .testTag("live_upload_speed_text")
                                .semantics { contentDescription = uploadDescription }
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = ulUnit,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = secondary()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = glassBorder.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(12.dp))

            // 1-minute average + session total (both real, both measured)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                LiveStat(
                    label = stringResource(R.string.hero_one_minute_average),
                    value = "↓ ${SpeedFormatter.format(live.averageDownloadBps, speedUnit)}",
                    sub = "↑ ${SpeedFormatter.format(live.averageUploadBps, speedUnit)}"
                )
                LiveStat(
                    label = stringResource(R.string.hero_this_session),
                    value = BytesFormatter.format(live.sessionBytes),
                    sub = sessionDurationLabel(live.sessionDurationMs)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Per-network attribution: real bytes split by the transport that carried them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TransportSpeedChip(
                    label = stringResource(R.string.network_wifi).uppercase(java.util.Locale.getDefault()),
                    iconRes = R.drawable.ic_wifi,
                    downloadBps = live.wifiDownloadBps,
                    uploadBps = live.wifiUploadBps,
                    tint = downColor,
                    speedUnit = speedUnit,
                    modifier = Modifier.weight(1f)
                )
                TransportSpeedChip(
                    label = stringResource(R.string.network_mobile).uppercase(java.util.Locale.getDefault()),
                    iconRes = R.drawable.ic_cellular,
                    downloadBps = live.mobileDownloadBps,
                    uploadBps = live.mobileUploadBps,
                    tint = upColor,
                    speedUnit = speedUnit,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 30-second sparkline
            SpeedSparkline(dlHistory = live.downloadHistory, ulHistory = live.uploadHistory)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.hero_last_30_seconds),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = secondary().copy(alpha = 0.7f)
                )
                val waiting = !isRunning && !live.paused
                // "Waiting" breathes instead of sitting there: the engine is starting, and
                // a static label reads like a stuck one. Nothing is faked — the number
                // stays 0 until a real sample is published.
                val shimmer = rememberInfiniteTransition(label = "hero_waiting_shimmer")
                val waitingAlpha by shimmer.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "hero_waiting_alpha"
                )
                Text(
                    text = when {
                        live.paused -> stringResource(R.string.hero_status_paused)
                        isRunning -> stringResource(R.string.hero_status_realtime)
                        else -> stringResource(R.string.hero_status_waiting)
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = when {
                        live.paused -> AccentWarning
                        isRunning -> liveColor
                        else -> secondary().copy(alpha = if (waiting) waitingAlpha else 0.7f)
                    },
                    modifier = Modifier
                        .testTag("live_speed_status_label")
                        // Changes a few times per session at most — safe to announce.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

@Composable
private fun LiveStat(
    label: String,
    value: String,
    sub: String
) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.9.sp,
            color = secondary().copy(alpha = 0.75f)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = sub,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary()
        )
    }
}

/** Real bytes measured on one transport (attributed while it was the active network). */
@Composable
private fun TransportSpeedChip(
    label: String,
    iconRes: Int,
    downloadBps: Long,
    uploadBps: Long,
    tint: Color,
    speedUnit: SpeedUnit,
    modifier: Modifier = Modifier
) {
    val active = downloadBps > 0L || uploadBps > 0L
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(glassColor)
            .border(1.dp, glassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AppIcon(
            resId = iconRes,
            contentDescription = null,
            tint = if (active) tint else secondary().copy(alpha = 0.6f),
            modifier = Modifier.size(13.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.9.sp,
                color = secondary()
            )
            Text(
                text = "↓ ${SpeedFormatter.format(downloadBps, speedUnit)}  ↑ ${SpeedFormatter.format(uploadBps, speedUnit)}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (active) MaterialTheme.colorScheme.onBackground else secondary()
            )
        }
    }
}

@Composable
private fun networkLabel(transport: Transport): String = when (transport) {
    Transport.WIFI -> stringResource(R.string.network_wifi)
    Transport.MOBILE -> stringResource(R.string.network_mobile)
    Transport.ETHERNET -> stringResource(R.string.network_ethernet)
    Transport.VPN -> stringResource(R.string.network_vpn)
    Transport.NONE -> stringResource(R.string.network_offline)
}

private fun networkIcon(transport: Transport): Int = when (transport) {
    Transport.WIFI, Transport.ETHERNET -> R.drawable.ic_wifi
    Transport.MOBILE -> R.drawable.ic_cellular
    Transport.VPN -> R.drawable.ic_shield
    Transport.NONE -> R.drawable.ic_warning
}

@Composable
private fun sessionDurationLabel(durationMs: Long): String {
    if (durationMs <= 0L) return stringResource(R.string.hero_session_just_started)
    val totalMinutes = durationMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        stringResource(R.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.duration_minutes, minutes)
    }
}

/**
 * Live graph of the last 30 published seconds: download (area + line) and upload
 * (area + line). Both series share one scale so the two directions are comparable.
 */
@Composable
fun SpeedSparkline(
    dlHistory: List<Long>,
    ulHistory: List<Long>,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkModeActive()
    val downColor = if (isDark) SpeedDownDark else SpeedDown
    val upColor = if (isDark) SpeedUpDark else SpeedUp

    Canvas(modifier = modifier.fillMaxWidth().height(54.dp)) {
        val points = 30

        fun samples(list: List<Long>): List<Float> {
            val recent = list.takeLast(points)
            return (List(points - recent.size) { 0f } + recent.map { it.toFloat() })
        }

        val dl = samples(dlHistory)
        val ul = samples(ulHistory)
        // Shared scale across BOTH directions, otherwise a fast upload would be clipped.
        val peak = maxOf(dl.maxOrNull() ?: 0f, ul.maxOrNull() ?: 0f)
        val maxVal = peak.coerceAtLeast(1024f) // 1 KB/s baseline

        fun toPath(vals: List<Float>): Path {
            val path = Path()
            vals.forEachIndexed { i, v ->
                val x = i.toFloat() / (points - 1) * size.width
                val y = size.height - (v / maxVal * size.height).coerceIn(0f, size.height)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        fun areaPath(vals: List<Float>): Path {
            val path = Path()
            addPathTo(path, toPath(vals), size.width, size.height)
            return path
        }

        drawPath(
            areaPath(dl),
            Brush.verticalGradient(listOf(downColor.copy(alpha = 0.28f), downColor.copy(alpha = 0.02f)))
        )
        drawPath(
            areaPath(ul),
            Brush.verticalGradient(listOf(upColor.copy(alpha = 0.22f), upColor.copy(alpha = 0.02f)))
        )
        drawPath(toPath(dl), downColor, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        drawPath(toPath(ul), upColor, style = Stroke(width = 1.8f, cap = StrokeCap.Round))

        // Head marker on the newest sample of each series.
        listOf(dl to downColor, ul to upColor).forEach { (values, color) ->
            val last = values.lastOrNull() ?: return@forEach
            val x = size.width
            val y = size.height - (last / maxVal * size.height).coerceIn(0f, size.height)
            drawCircle(color = color, radius = 3.2f, center = Offset(x, y))
            drawCircle(color = color.copy(alpha = 0.25f), radius = 6f, center = Offset(x, y))
        }
    }
}

private fun addPathTo(target: Path, source: Path, width: Float, height: Float) {
    target.addPath(source)
    target.lineTo(width, height)
    target.lineTo(0f, height)
    target.close()
}

// ============================== USAGE CARDS ==============================

@Composable
fun UsageCard(
    title: String,
    iconRes: Int,
    data: DataUsage,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkModeActive()
    val accent = if (isDark) LiveGreenDark else LiveGreen

    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(
                        resId = iconRes,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.label_today),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                color = secondary()
            )
            Text(
                text = BytesFormatter.format(data.total.toBytes()),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glassBorder.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat(
                    iconRes = R.drawable.ic_arrow_down,
                    label = stringResource(R.string.label_down),
                    valueMb = data.download,
                    tint = SpeedDownC()
                )
                MiniStat(
                    iconRes = R.drawable.ic_arrow_up,
                    label = stringResource(R.string.label_up),
                    valueMb = data.upload,
                    tint = SpeedUpC()
                )
            }
        }
    }
}

@Composable
private fun MiniStat(
    iconRes: Int,
    label: String,
    valueMb: Double,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppIcon(
            resId = iconRes,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
                color = secondary()
            )
            Text(
                text = BytesFormatter.format(valueMb.toBytes()),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

// ============================== TOTAL CARD ==============================

@Composable
fun TotalCard(
    data: DataUsage,
    limitProgress: Double?,
    limitLabel: String,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkModeActive()
    // Inverse-contrast "ink" card: black on light, white on dark (GPT style).
    val cardColor = if (isDark) Color(0xFFF2F4F8) else Color(0xFF15181D)
    val onCard = if (isDark) Color(0xFF15181D) else Color(0xFFF5F6F8)
    val muted = onCard.copy(alpha = 0.55f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.18f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.22f)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(cardColor)
            .border(1.dp, if (isDark) Color(0x33000000) else Color(0x29FFFFFF), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.total_today_title),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                color = muted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = BytesFormatter.format(data.total.toBytes()),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = onCard
            )

            if (limitProgress != null) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { limitProgress.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(100.dp))
                        .height(6.dp),
                    color = onCard,
                    trackColor = onCard.copy(alpha = 0.15f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.total_mobile_limit, limitLabel),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp,
                        color = muted
                    )
                    Text(
                        text = "${(limitProgress * 100).toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = onCard
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MiniStatOnInk(
                        iconRes = R.drawable.ic_arrow_down,
                        label = stringResource(R.string.label_download),
                        valueMb = data.download,
                        onCard = onCard,
                        muted = muted
                    )
                    MiniStatOnInk(
                        iconRes = R.drawable.ic_arrow_up,
                        label = stringResource(R.string.label_upload),
                        valueMb = data.upload,
                        onCard = onCard,
                        muted = muted
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStatOnInk(
    iconRes: Int,
    label: String,
    valueMb: Double,
    onCard: Color,
    muted: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppIcon(
            resId = iconRes,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(13.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
                color = muted
            )
            Text(
                text = BytesFormatter.format(valueMb.toBytes()),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = onCard
            )
        }
    }
}

// ============================== PERMISSION HINT ==============================

@Composable
fun UsageAccessHintCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    GlassCard(modifier = modifier.fillMaxWidth().testTag("usage_access_hint_card")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(com.example.ui.theme.AccentWarning.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(
                    resId = R.drawable.ic_shield,
                    contentDescription = null,
                    tint = com.example.ui.theme.AccentWarning,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.usage_access_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.usage_access_hint_body),
                    fontSize = 11.sp,
                    color = secondary()
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .testTag("grant_permission_button")
            ) {
                Text(
                    text = stringResource(R.string.action_grant),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
