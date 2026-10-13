package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.SpeedTestResult
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
import com.example.ui.util.secondary
import com.example.ui.viewmodel.SpeedTestPhase
import com.example.ui.viewmodel.SpeedTestState
import com.example.ui.viewmodel.SpeedTestViewModel
import com.example.util.BytesFormatter
import com.example.util.GaugeScale
import com.example.util.SpeedFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Active speed test: ping, download and upload are measured for real over the network,
 * which is the only way to know the *capacity* of a connection (the passive meter shows
 * what the device is currently using, which is 0 while idle).
 */
@Composable
fun SpeedTestScreen(
    viewModel: SpeedTestViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val context = LocalContext.current

    val isDark = isDarkModeActive()
    val downColor = if (isDark) SpeedDownDark else SpeedDown
    val upColor = if (isDark) SpeedUpDark else SpeedUp
    val liveColor = if (isDark) LiveGreenDark else LiveGreen

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("speed_test_screen_root")
    ) {
        AppBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
                    text = stringResource(R.string.test_eyebrow),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondary()
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.test_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // ---------------- Gauge ----------------
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("speed_test_gauge_card")) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SpeedGauge(
                        state = state,
                        downColor = downColor,
                        upColor = upColor,
                        liveColor = liveColor
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // Phase label cross-fades instead of snapping: the test reads as one
                    // continuous instrument instead of three separate screens.
                    Crossfade(
                        targetState = state.phase,
                        animationSpec = tween(durationMillis = 320),
                        label = "speed_test_phase_crossfade"
                    ) { phase ->
                        Text(
                            text = phaseLabel(phase, state.server),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when (phase) {
                                SpeedTestPhase.ERROR -> AccentDanger
                                SpeedTestPhase.DONE -> liveColor
                                else -> secondary()
                            },
                            modifier = Modifier.testTag("speed_test_phase_label")
                        )
                    }
                }
            }

            // ---------------- Metrics ----------------
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricTile(
                        label = stringResource(R.string.metric_ping),
                        value = state.latencyMs?.toString() ?: "—",
                        unit = "ms",
                        tint = AccentWarning,
                        testTag = "speed_test_ping_value"
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(glassBorder)
                    )
                    MetricTile(
                        label = stringResource(R.string.metric_download),
                        value = state.downloadBitsPerSecond?.let { SpeedFormatter.splitBits(it).first } ?: "—",
                        unit = state.downloadBitsPerSecond?.let { SpeedFormatter.splitBits(it).second } ?: "Mbps",
                        tint = downColor,
                        testTag = "speed_test_download_value"
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(44.dp)
                            .background(glassBorder)
                    )
                    MetricTile(
                        label = stringResource(R.string.metric_upload),
                        value = state.uploadBitsPerSecond?.let { SpeedFormatter.splitBits(it).first } ?: "—",
                        unit = state.uploadBitsPerSecond?.let { SpeedFormatter.splitBits(it).second } ?: "Mbps",
                        tint = upColor,
                        testTag = "speed_test_upload_value"
                    )
                }
            }

            // ---------------- Result detail ----------------
            if (state.phase == SpeedTestPhase.DONE) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.result_latest),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                color = secondary()
                            )
                            // Share is a plain ACTION_SEND text intent — no SDK, no image,
                            // nothing leaves the device except what the user taps.
                            ShareChip(onShare = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, viewModel.shareText())
                                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_subject))
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching {
                                    context.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        context.getString(R.string.share_chooser)
                                    )
                                )
                                }
                            })
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        ResultRow(stringResource(R.string.result_download_server), state.server)
                        if (state.uploadServer != null && state.uploadServer != state.server) {
                            ResultRow(stringResource(R.string.result_upload_server), state.uploadServer)
                        }
                        if (state.uploadUnavailable) {
                            ResultRow(
                            stringResource(R.string.result_upload),
                            stringResource(R.string.result_upload_unavailable)
                        )
                        }
                        ResultRow(stringResource(R.string.result_jitter), state.jitterMs?.let { "$it ms" } ?: "—")
                        ResultRow(
                            stringResource(R.string.result_download),
                            state.downloadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                        )
                        ResultRow(
                            stringResource(R.string.result_upload),
                            state.uploadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                        )
                        // What the test cost, measured — not an estimate.
                        if (state.bytesUsed > 0L) {
                            ResultRow(stringResource(R.string.result_data_used), BytesFormatter.format(state.bytesUsed))
                        }
                        ResultRow(stringResource(R.string.result_completed), formatTimestamp(state.finishedAtMillis))
                    }
                }
            }

            if (state.phase == SpeedTestPhase.ERROR) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_warning,
                            contentDescription = null,
                            tint = AccentDanger,
                            modifier = Modifier.size(16.dp)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.test_error_title),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = state.error ?: stringResource(R.string.test_error_body),
                                fontSize = 11.sp,
                                color = secondary()
                            )
                        }
                    }
                }
            }

            // ---------------- Action button ----------------
            Button(
                onClick = { if (state.isRunning) viewModel.cancelTest() else viewModel.startTest() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp)
                    .height(52.dp)
                    .testTag("speed_test_start_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                AppIcon(
                    resId = R.drawable.ic_bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        state.isRunning -> stringResource(R.string.action_stop_test)
                        state.phase == SpeedTestPhase.DONE || state.phase == SpeedTestPhase.ERROR ->
                            stringResource(R.string.action_test_again)
                        else -> stringResource(R.string.action_start_test)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.test_footer),
                fontSize = 10.sp,
                color = secondary().copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )

            // ---------------- History ----------------
            if (history.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 6.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.test_history),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondary()
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(glassColor)
                            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("speed_test_clear_history")
                            .clickable { viewModel.clearHistory() }
                    ) {
                        Text(
                            text = stringResource(R.string.action_clear),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentDanger
                        )
                    }
                }
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        history.forEachIndexed { index, result ->
                            if (index > 0) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            HistoryRow(result)
                        }
                    }
                }
            }
        }

        // ---------------- Metered-network confirmation ----------------
        if (state.awaitingMeteredConfirmation) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissMeteredWarning() },
                title = { Text(stringResource(R.string.metered_title), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.metered_body),
                        color = secondary()
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmMeteredNetwork() }) {
                        Text(stringResource(R.string.action_run_anyway), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissMeteredWarning() }) {
                        Text(stringResource(R.string.action_cancel), color = secondary())
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("speed_test_metered_dialog")
            )
        }
    }
}

@Composable
private fun phaseLabel(phase: SpeedTestPhase, server: String): String = when (phase) {
    SpeedTestPhase.IDLE -> stringResource(R.string.phase_idle)
    SpeedTestPhase.PING -> stringResource(R.string.phase_ping, server.uppercase(Locale.ROOT))
    SpeedTestPhase.DOWNLOAD -> stringResource(R.string.phase_download)
    SpeedTestPhase.UPLOAD -> stringResource(R.string.phase_upload)
    SpeedTestPhase.DONE -> stringResource(R.string.phase_done)
    SpeedTestPhase.ERROR -> stringResource(R.string.phase_failed)
}

@Composable
private fun ShareChip(onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(glassColor)
            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onShare)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("speed_test_share_button"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        AppIcon(
            resId = R.drawable.ic_share,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = stringResource(R.string.action_share),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * The gauge: a tick scale, a progress arc and a needle that sweeps to the measured value.
 *
 * The scale is picked from a fixed ladder ([GaugeScale]) rather than derived from the
 * current peak, so the needle has a stable, meaningful position (a scale that resizes
 * itself every tick would make the instrument unreadable).
 */
@Composable
private fun SpeedGauge(
    state: SpeedTestState,
    downColor: Color,
    upColor: Color,
    liveColor: Color
) {
    val accent = when (state.phase) {
        SpeedTestPhase.PING -> AccentWarning
        SpeedTestPhase.UPLOAD -> upColor
        SpeedTestPhase.DONE -> liveColor
        SpeedTestPhase.ERROR -> AccentDanger
        else -> downColor
    }
    val maxBps = GaugeScale.maxBps(state.gaugeBitsPerSecond)
    val targetRatio = (state.gaugeBitsPerSecond / maxBps).toFloat().coerceIn(0f, 1f)

    val animatedBits by animateFloatAsState(
        targetValue = state.gaugeBitsPerSecond.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "speed_test_gauge_value"
    )
    // Needle sweeps with a spring so it settles like a real instrument.
    val needleRatio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 120f),
        label = "speed_test_gauge_needle"
    )
    val (number, unit) = SpeedFormatter.splitBits(animatedBits.toDouble())
    val gaugeTrackColor = glassBorder.copy(alpha = 0.6f)
    val tickColor = secondary().copy(alpha = 0.55f)
    // Resolved before the Canvas lambda: it is not a @Composable scope in there.
    val hubColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier.size(224.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweep = 270f
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = arcSize.width / 2f

            drawArc(
                color = gaugeTrackColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            val ratio = state.phaseProgress.coerceIn(0f, 1f)
            if (ratio > 0.001f) {
                drawArc(
                    color = accent,
                    startAngle = startAngle,
                    sweepAngle = sweep * ratio,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // ---- tick scale: 10 minor steps between 11 major ticks ----
            val majors = 10
            val minorsPerMajor = 5
            repeat(majors * minorsPerMajor + 1) { index ->
                val fraction = index.toFloat() / (majors * minorsPerMajor)
                val isMajor = index % minorsPerMajor == 0
                val angle = Math.toRadians((startAngle + sweep * fraction).toDouble())
                val inner = radius - (if (isMajor) 15.dp.toPx() else 8.dp.toPx())
                val outer = radius - 2.dp.toPx()
                drawLine(
                    color = if (isMajor) tickColor else tickColor.copy(alpha = 0.35f),
                    start = Offset(
                        center.x + inner * cos(angle).toFloat(),
                        center.y + inner * sin(angle).toFloat()
                    ),
                    end = Offset(
                        center.x + outer * cos(angle).toFloat(),
                        center.y + outer * sin(angle).toFloat()
                    ),
                    strokeWidth = if (isMajor) 2f else 1f,
                    cap = StrokeCap.Round
                )
            }

            // ---- needle ----
            val needleAngle = Math.toRadians((startAngle + sweep * needleRatio).toDouble())
            drawLine(
                color = accent,
                start = center,
                end = Offset(
                    center.x + (radius - 22.dp.toPx()) * cos(needleAngle).toFloat(),
                    center.y + (radius - 22.dp.toPx()) * sin(needleAngle).toFloat()
                ),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
            drawCircle(color = accent, radius = 7.dp.toPx(), center = center)
            drawCircle(color = hubColor, radius = 3.dp.toPx(), center = center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = number,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("speed_test_gauge_number")
            )
            Text(
                text = unit,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = accent
            )
        }
    }

    // Scale end labels live in Compose (no canvas text), so they stay crisp and legible.
    val (maxValue, maxUnit) = SpeedFormatter.splitBits(maxBps)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 34.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "0",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary().copy(alpha = 0.7f)
        )
        Text(
            text = "$maxValue $maxUnit",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary().copy(alpha = 0.7f)
        )
    }
}

/**
 * Metric tile that animates when a value "lands" — a number appearing out of nowhere
 * reads as a glitch; a number settling into place reads as a measurement.
 */
@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    tint: Color,
    testTag: String
) {
    var landed by remember(value) { mutableStateOf(value == "—") }
    LaunchedEffect(value) { landed = true }
    val scale by animateFloatAsState(
        targetValue = if (landed) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 320f),
        label = "metric_landing_$testTag"
    )
    val alpha by animateFloatAsState(
        targetValue = if (landed) 1f else 0.35f,
        animationSpec = tween(durationMillis = 260),
        label = "metric_landing_alpha_$testTag"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = (22 * scale).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                modifier = Modifier.testTag(testTag)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = unit,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = tint.copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = secondary())
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun HistoryRow(result: SpeedTestResult) {
    val isDark = isDarkModeActive()
    val downColor = if (isDark) SpeedDownDark else SpeedDown
    val upColor = if (isDark) SpeedUpDark else SpeedUp

    Column {
        Text(
            text = formatTimestamp(result.atMillis),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassChip {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppIcon(
                        resId = R.drawable.ic_arrow_down,
                        contentDescription = null,
                        tint = downColor,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = SpeedFormatter.formatBits(result.downloadBps * 8.0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            GlassChip {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AppIcon(
                        resId = R.drawable.ic_arrow_up,
                        contentDescription = null,
                        tint = upColor,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = if (result.uploadBps > 0.0) SpeedFormatter.formatBits(result.uploadBps * 8.0) else "—",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            Text(
                text = "${result.latencyMs} ms",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary()
            )
            Text(
                text = BytesFormatter.format(result.bytesUsed),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary().copy(alpha = 0.85f)
            )
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
