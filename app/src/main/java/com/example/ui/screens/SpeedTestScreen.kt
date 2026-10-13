package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.SpeedTestResult
import com.example.services.SpeedTestEngine
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
                    text = "REAL MEASUREMENT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondary()
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "Speed Test",
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
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SpeedGauge(
                        state = state,
                        downColor = downColor,
                        upColor = upColor,
                        liveColor = liveColor
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when (state.phase) {
                            SpeedTestPhase.IDLE -> "READY — TAP START"
                            SpeedTestPhase.PING -> "PINGING ${state.server.uppercase(Locale.ROOT)}"
                            SpeedTestPhase.DOWNLOAD -> "MEASURING DOWNLOAD…"
                            SpeedTestPhase.UPLOAD -> "MEASURING UPLOAD…"
                            SpeedTestPhase.DONE -> "TEST COMPLETE"
                            SpeedTestPhase.ERROR -> "TEST FAILED"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when (state.phase) {
                            SpeedTestPhase.ERROR -> AccentDanger
                            SpeedTestPhase.DONE -> liveColor
                            else -> secondary()
                        },
                        modifier = Modifier.testTag("speed_test_phase_label")
                    )
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
                        label = "PING",
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
                        label = "DOWNLOAD",
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
                        label = "UPLOAD",
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
                        Text(
                            text = "LATEST RESULT",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace,
                            color = secondary()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        ResultRow("Server", state.server)
                        ResultRow("Jitter", state.jitterMs?.let { "$it ms" } ?: "—")
                        ResultRow(
                            "Download",
                            state.downloadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                        )
                        ResultRow(
                            "Upload",
                            state.uploadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                        )
                        ResultRow("Completed", formatTimestamp(state.finishedAtMillis))
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
                                text = "Could not finish the test",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = state.error ?: "Check your connection and try again.",
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
                        state.isRunning -> "STOP TEST"
                        state.phase == SpeedTestPhase.DONE || state.phase == SpeedTestPhase.ERROR -> "TEST AGAIN"
                        else -> "START TEST"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Runs only when you tap the button. No account, no analytics — " +
                    "the test talks to ${SpeedTestEngine.SERVER_NAME} and nothing else.",
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
                        text = "TEST HISTORY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondary()
                    )
                    Text(
                        text = "CLEAR",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AccentDanger,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(glassColor)
                            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("speed_test_clear_history")
                            .styleClickable { viewModel.clearHistory() }
                    )
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
    }
}

/** Reusable "make this chip tappable" helper (keeps the call sites tidy). */
private fun Modifier.styleClickable(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

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
    val animatedBits by animateFloatAsState(
        targetValue = state.gaugeBitsPerSecond.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "speed_test_gauge_value"
    )
    val (number, unit) = SpeedFormatter.splitBits(animatedBits.toDouble())

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

            drawArc(
                color = glassBorder.copy(alpha = 0.6f),
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
                // Little "head" dot so the sweep reads as motion.
                val angle = Math.toRadians((startAngle + sweep * ratio).toDouble())
                val radius = arcSize.width / 2f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                drawCircle(
                    color = accent,
                    radius = strokeWidth / 2.6f,
                    center = Offset(
                        centerX + radius * cos(angle).toFloat(),
                        centerY + radius * sin(angle).toFloat()
                    )
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    unit: String,
    tint: Color,
    testTag: String
) {
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
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag(testTag)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = unit,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = tint
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
                        text = SpeedFormatter.formatBits(result.uploadBps * 8.0),
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
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
