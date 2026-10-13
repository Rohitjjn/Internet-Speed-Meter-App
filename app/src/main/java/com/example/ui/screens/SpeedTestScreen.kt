package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.SpeedTestResult
import com.example.services.SpeedTestServers
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
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Active speed test: ping, download and upload are measured for real over the network,
 * which is the only way to know the *capacity* of a connection (the passive meter shows
 * what the device is currently using, which is 0 while idle).
 *
 * The gauge is a real instrument: a labelled value scale, tick marks, and a needle that
 * follows the live measurement — plus a separate thin bar for how far the current phase
 * is through its time budget. Result sharing is a plain text `ACTION_SEND`.
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
                    Spacer(modifier = Modifier.height(10.dp))
                    PhaseProgressBar(state = state, accent = gaugeAccent(state, downColor, upColor, liveColor))
                    Spacer(modifier = Modifier.height(12.dp))
                    AnimatedContent(
                        targetState = state.phase,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(240)) togetherWith
                                fadeOut(animationSpec = tween(140))
                        },
                        label = "speed_test_phase_crossfade"
                    ) { phase ->
                        Text(
                            text = phaseLabel(phase, state),
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.usedFallback) {
                            "${state.server} · fallback endpoint"
                        } else {
                            state.server
                        },
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondary().copy(alpha = 0.75f),
                        modifier = Modifier.testTag("speed_test_server_label")
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
                        bitsPerSecond = null,
                        latencyMs = state.latencyMs,
                        unitForLatency = "ms",
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
                        bitsPerSecond = state.downloadBitsPerSecond,
                        latencyMs = null,
                        unitForLatency = "Mbps",
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
                        bitsPerSecond = state.uploadBitsPerSecond,
                        latencyMs = null,
                        unitForLatency = "Mbps",
                        tint = upColor,
                        testTag = "speed_test_upload_value"
                    )
                }
            }

            // ---------------- Result detail + share ----------------
            if (state.phase == SpeedTestPhase.DONE) {
                GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LATEST RESULT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                color = secondary()
                            )
                            Text(
                                text = "SHARE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(glassColor)
                                    .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("speed_test_share_button")
                                    .clickable { shareResult(context, state) }
                            )
                        }
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
                        if (state.usedFallback) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "The default endpoint did not answer, so this result comes " +
                                    "from the fallback host.",
                                fontSize = 10.sp,
                                color = secondary().copy(alpha = 0.85f)
                            )
                        }
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
                                text = if (state.aborted) {
                                    "Test stopped"
                                } else {
                                    "Could not finish the test"
                                },
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
                onClick = {
                    if (state.isRunning) viewModel.cancelTest() else viewModel.requestTest()
                },
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
                text = "Runs only when you tap the button. The test talks to one public " +
                    "measurement endpoint (${SpeedTestServers.CLOUDFLARE.host} by default) and " +
                    "nothing else — no account, no analytics. On mobile data it asks first, " +
                    "because a test really does use some of your data.",
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
                            .clickable { viewModel.clearHistory() }
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

    // ---------------- Mobile-data confirmation ----------------
    state.pendingConfirmationMb?.let { estimateMb ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmation() },
            title = { Text("Use about $estimateMb MB?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "You are on mobile data and the test measures the real line speed, so it " +
                        "transfers roughly $estimateMb MB (8 s download + 6 s upload at whatever " +
                        "your connection can do).\n\nSkipping keeps your data untouched.",
                    color = secondary()
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmStart() }) {
                    Text("RUN TEST", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirmation() }) {
                    Text("CANCEL", color = secondary())
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// ============================== GAUGE ==============================

/** Accent used by the gauge/needle for the current phase. */
@Composable
private fun gaugeAccent(
    state: SpeedTestState,
    downColor: Color,
    upColor: Color,
    liveColor: Color
): Color = when (state.phase) {
    SpeedTestPhase.PING -> AccentWarning
    SpeedTestPhase.UPLOAD -> upColor
    SpeedTestPhase.DONE -> liveColor
    SpeedTestPhase.ERROR -> AccentDanger
    else -> downColor
}

private fun phaseLabel(phase: SpeedTestPhase, state: SpeedTestState): String = when (phase) {
    SpeedTestPhase.IDLE -> "READY — TAP START"
    SpeedTestPhase.PING -> "PINGING ${state.server.uppercase(Locale.ROOT)}"
    SpeedTestPhase.DOWNLOAD -> "MEASURING DOWNLOAD…"
    SpeedTestPhase.UPLOAD -> "MEASURING UPLOAD…"
    SpeedTestPhase.DONE -> "TEST COMPLETE"
    SpeedTestPhase.ERROR -> if (state.aborted) "TEST STOPPED" else "TEST FAILED"
}

/** How far the current phase (8 s download / 6 s upload) is through its time budget. */
@Composable
private fun PhaseProgressBar(state: SpeedTestState, accent: Color) {
    val progress = if (state.phase == SpeedTestPhase.DONE) 1f else state.phaseProgress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 220),
        label = "speed_test_phase_progress"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(glassBorder.copy(alpha = 0.65f))
    ) {
        val visible = (animated > 0.001f && state.isRunning) || state.phase == SpeedTestPhase.DONE
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(accent)
            )
        }
    }
}

/**
 * Instrument-style gauge: labelled value scale, major/minor tick marks and a needle that
 * sweeps to the live measurement. The scale ceiling is chosen from what this run has
 * actually observed (never from a made-up "max"), so a 30 Mbit/s line and a 600 Mbit/s
 * line both get a readable dial.
 */
@Composable
private fun SpeedGauge(
    state: SpeedTestState,
    downColor: Color,
    upColor: Color,
    liveColor: Color
) {
    val accent = gaugeAccent(state, downColor, upColor, liveColor)

    // Highest real value seen during this run — the dial's ceiling is derived from it.
    var observedMaxMbps by remember(state.startedAtMillis) { mutableFloatStateOf(0f) }
    val currentMbps = (state.gaugeBitsPerSecond / 1_000_000.0).toFloat()
    LaunchedEffect(currentMbps) {
        if (currentMbps > observedMaxMbps) observedMaxMbps = currentMbps
    }
    val ceilingMbps = niceCeilingMbps(observedMaxMbps)

    val targetFraction = if (state.phase == SpeedTestPhase.PING || state.phase == SpeedTestPhase.IDLE) {
        0f
    } else {
        (currentMbps / ceilingMbps).coerceIn(0f, 1f)
    }
    val needleFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "speed_test_needle"
    )

    val trackColor = glassBorder.copy(alpha = 0.65f)
    val tickColor = secondary().copy(alpha = 0.55f)
    val tickMajorColor = secondary().copy(alpha = 0.85f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        color = secondary()
    )

    val (number, unit) = if (state.phase == SpeedTestPhase.PING && state.latencyMs != null) {
        state.latencyMs.toString() to "ms"
    } else {
        SpeedFormatter.splitBits(state.gaugeBitsPerSecond)
    }

    Box(modifier = Modifier.size(232.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 2.5.dp.toPx()
            val inset = strokeWidth / 2f + 16.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = min(size.width, size.height) / 2f - inset
            val startAngle = 135f
            val sweep = 270f

            // Track
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Ticks: 10 major marks with 4 minor marks in between.
            val majorTicks = 10
            val minorPerGap = 4
            val totalTicks = majorTicks * minorPerGap
            for (i in 0..totalTicks) {
                val isMajor = i % minorPerGap == 0
                val angle = Math.toRadians((startAngle + sweep * (i.toFloat() / totalTicks)).toDouble())
                val outer = radius + 9.dp.toPx()
                val inner = outer - if (isMajor) 9.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = if (isMajor) tickMajorColor else tickColor,
                    start = Offset(
                        center.x + inner * cos(angle).toFloat(),
                        center.y + inner * sin(angle).toFloat()
                    ),
                    end = Offset(
                        center.x + outer * cos(angle).toFloat(),
                        center.y + outer * sin(angle).toFloat()
                    ),
                    strokeWidth = if (isMajor) 1.8.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Scale labels at 0 / 25 / 50 / 75 / 100 % of the ceiling.
            for (step in 0..4) {
                val fraction = step / 4f
                val angle = Math.toRadians((startAngle + sweep * fraction).toDouble())
                val labelRadius = radius - 15.dp.toPx()
                val value = ceilingMbps * fraction
                val text = if (ceilingMbps <= 10f) {
                    String.format(Locale.US, "%.1f", value)
                } else {
                    value.roundToLong().toString()
                }
                val layout = measurer.measure(AnnotatedString(text), style = labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        center.x + labelRadius * cos(angle).toFloat() - layout.size.width / 2f,
                        center.y + labelRadius * sin(angle).toFloat() - layout.size.height / 2f
                    )
                )
            }

            // Needle
            val needleAngle = Math.toRadians((startAngle + sweep * needleFraction).toDouble())
            val needleLength = radius * 0.74f
            drawLine(
                color = accent,
                start = center,
                end = Offset(
                    center.x + needleLength * cos(needleAngle).toFloat(),
                    center.y + needleLength * sin(needleAngle).toFloat()
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(color = accent, radius = 6.dp.toPx(), center = center)
            drawCircle(color = trackColor, radius = 2.5.dp.toPx(), center = center)
        }

        Column(
            modifier = Modifier.padding(top = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = number,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag("speed_test_gauge_number")
            )
            Text(
                text = unit,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = accent
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (state.phase == SpeedTestPhase.PING) "LATENCY" else "SCALE 0–${ceilingLabel(ceilingMbps)} Mbps",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                color = secondary().copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * The smallest "round" dial ceiling that fits [observedMbps] with headroom, so the needle
 * spends most of the run in the readable part of the dial instead of pinned at the edge.
 */
internal fun niceCeilingMbps(observedMbps: Float): Float {
    val candidates = floatArrayOf(10f, 25f, 50f, 100f, 250f, 500f, 1000f, 2500f)
    val target = (observedMbps * 1.15f).coerceAtLeast(10f)
    // Powers-of-ten rounding keeps the label digits short (10/25/50/100/250/500/1000).
    return candidates.firstOrNull { it >= target } ?: candidates.last()
}

/** Short label for the dial ceiling — whole numbers only, no stray ".0" (`1000`, `250`). */
private fun ceilingLabel(ceilingMbps: Float): String = ceilingMbps.roundToLong().toString()

// ============================== SHARING ==============================

/** Plain `ACTION_SEND` text — no SDK, no image, no account. */
private fun shareResult(context: android.content.Context, state: SpeedTestState) {
    val download = state.downloadBitsPerSecond ?: 0.0
    val upload = state.uploadBitsPerSecond ?: 0.0
    val ping = state.latencyMs ?: 0L
    val jitter = state.jitterMs ?: 0L
    val text = buildString {
        append("Internet Speed Meter — speed test result\n")
        append("↓ ${SpeedFormatter.formatBits(download)}   ↑ ${SpeedFormatter.formatBits(upload)}\n")
        append("Ping $ping ms · Jitter $jitter ms\n")
        append("Server ${state.server}\n")
        append("${formatTimestamp(state.finishedAtMillis)}")
    }
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share speed test"))
    } catch (e: Exception) {
        // A missing share target must never crash the test screen.
        e.printStackTrace()
    }
}

// ============================== SMALL PARTS ==============================

/**
 * One metric of the result row. Values animate to their new reading instead of popping in,
 * and a not-yet-measured value stays a dash (never a fabricated `0`).
 */
@Composable
private fun MetricTile(
    label: String,
    bitsPerSecond: Double?,
    latencyMs: Long?,
    unitForLatency: String,
    tint: Color,
    testTag: String
) {
    val animatedBits by animateFloatAsState(
        targetValue = (bitsPerSecond ?: 0.0).toFloat(),
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "${testTag}_value"
    )
    val animatedLatency by animateFloatAsState(
        targetValue = (latencyMs ?: 0L).toFloat(),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "${testTag}_latency"
    )

    val value: String
    val unit: String
    if (latencyMs != null || unitForLatency == "ms") {
        value = if (latencyMs == null) "—" else animatedLatency.roundToLong().toString()
        unit = unitForLatency
    } else {
        val split = SpeedFormatter.splitBits(animatedBits.toDouble())
        value = if (bitsPerSecond == null) "—" else split.first
        unit = split.second
    }

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
