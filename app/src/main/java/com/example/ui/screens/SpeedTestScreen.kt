package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import com.example.services.SpeedTestEngine
import com.example.services.SpeedTestServers
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
import com.example.ui.util.secondary
import com.example.ui.viewmodel.SpeedTestPhase
import com.example.ui.viewmodel.SpeedTestState
import com.example.ui.viewmodel.SpeedTestViewModel
import com.example.util.CsvExport
import com.example.util.DeltaMath
import com.example.util.SpeedFormatter
import com.example.util.SpeedTestCsv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** Width from which the Test screen lays the gauge and the readouts side by side. */
private val TwoColumnBreakpoint = 600.dp

/**
 * Active speed test: ping, download and upload are measured for real over the network,
 * which is the only way to know the *capacity* of a connection (the passive meter shows
 * what the device is currently using, which is 0 while idle).
 *
 * The gauge is a real instrument: a labelled value scale, tick marks, a needle that
 * follows the live measurement and a dimmer **ghost needle** at the previous run's value
 * — plus a separate thin bar for how far the current phase is through its time budget.
 * Result sharing is a plain text `ACTION_SEND`.
 */
@Composable
fun SpeedTestScreen(
    viewModel: SpeedTestViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()

    // One light tick when a *new* result lands (not when the tab is re-entered with an old
    // one): the same tactile language as the limit warning, no permission involved.
    val haptics = LocalHapticFeedback.current
    var lastResultTick by remember { mutableLongStateOf(state.finishedAtMillis) }
    LaunchedEffect(state.phase, state.finishedAtMillis) {
        if (state.phase == SpeedTestPhase.DONE && state.finishedAtMillis != lastResultTick) {
            lastResultTick = state.finishedAtMillis
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val isDark = isDarkModeActive()
    val palette = GaugePalette(
        down = if (isDark) SpeedDownDark else SpeedDown,
        up = if (isDark) SpeedUpDark else SpeedUp,
        live = if (isDark) LiveGreenDark else LiveGreen
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("speed_test_screen_root")
    ) {
        AppBackground()
        val twoColumn = maxWidth >= TwoColumnBreakpoint

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

            if (twoColumn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GaugeCard(state = state, palette = palette)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MetricsCard(state = state, palette = palette)
                        ResultOrErrorCard(state = state, palette = palette, topPadding = 16.dp)
                        ActionButton(state = state, viewModel = viewModel, topPadding = 16.dp)
                        FootNote()
                    }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    GaugeCard(state = state, palette = palette)
                    Spacer(modifier = Modifier.height(16.dp))
                    MetricsCard(state = state, palette = palette)
                    ResultOrErrorCard(state = state, palette = palette, topPadding = 16.dp)
                    ActionButton(state = state, viewModel = viewModel, topPadding = 16.dp)
                    FootNote()
                }
            }

            // ---------------- History ----------------
            if (history.isNotEmpty()) {
                val historyContext = LocalContext.current
                HistorySection(
                    history = history,
                    onClear = { viewModel.clearHistory() },
                    onExport = {
                        CsvExport.share(
                            context = historyContext,
                            csv = SpeedTestCsv.build(history),
                            fileName = CsvExport.fileName("speed-tests"),
                            subject = historyContext.getString(R.string.test_export_subject),
                            chooserTitle = historyContext.getString(R.string.test_export_chooser)
                        )
                    }
                )
            }
        }
    }

    // ---------------- Metered-data confirmation ----------------
    state.pendingConfirmationMb?.let { estimateMb ->
        MeteredConfirmationDialog(
            estimateMb = estimateMb,
            transport = state.pendingConfirmationTransport,
            dataSaverOn = state.pendingConfirmationDataSaver,
            onConfirm = { viewModel.confirmStart() },
            onDismiss = { viewModel.dismissConfirmation() }
        )
    }
}

/** The three colours the gauge, bars and tiles share. */
private class GaugePalette(val down: Color, val up: Color, val live: Color)

// ============================== CARDS ==============================

@Composable
private fun GaugeCard(state: SpeedTestState, palette: GaugePalette) {
    GlassCard(modifier = Modifier.fillMaxWidth().testTag("speed_test_gauge_card")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SpeedGauge(state = state, palette = palette)
            Spacer(modifier = Modifier.height(10.dp))
            PhaseProgressBar(state = state, accent = gaugeAccent(state, palette))
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
                        SpeedTestPhase.DONE -> palette.live
                        else -> secondary()
                    },
                    modifier = Modifier
                        .testTag("speed_test_phase_label")
                        // Phases change a handful of times per run: a polite live region
                        // tells a screen-reader user where the test is without spamming.
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (state.usedFallback) {
                    stringResource(R.string.test_server_fallback, state.server)
                } else {
                    state.server
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary().copy(alpha = 0.75f),
                modifier = Modifier.testTag("speed_test_server_label")
            )
        }
    }
}

@Composable
private fun MetricsCard(state: SpeedTestState, palette: GaugePalette) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricTile(
                label = stringResource(R.string.label_ping),
                bitsPerSecond = null,
                latencyMs = state.latencyMs,
                unitForLatency = stringResource(R.string.unit_ms),
                isLatency = true,
                tint = AccentWarning,
                testTag = "speed_test_ping_value"
            )
            MetricDivider()
            MetricTile(
                label = stringResource(R.string.label_download),
                bitsPerSecond = state.downloadBitsPerSecond,
                latencyMs = null,
                unitForLatency = "",
                isLatency = false,
                tint = palette.down,
                testTag = "speed_test_download_value"
            )
            MetricDivider()
            MetricTile(
                label = stringResource(R.string.label_upload),
                bitsPerSecond = state.uploadBitsPerSecond,
                latencyMs = null,
                unitForLatency = "",
                isLatency = false,
                tint = palette.up,
                testTag = "speed_test_upload_value"
            )
        }
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(44.dp)
            .background(glassBorder)
    )
}

@Composable
private fun ResultOrErrorCard(state: SpeedTestState, palette: GaugePalette, topPadding: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    if (state.phase == SpeedTestPhase.DONE) {
        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = topPadding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.test_latest_result),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondary()
                    )
                    val shareLabel = stringResource(R.string.a11y_share_result)
                    Text(
                        text = stringResource(R.string.action_share),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(glassColor)
                            .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = shareLabel, role = Role.Button) { shareResult(context, state) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("speed_test_share_button")
                    )
                }

                // "vs last test": computed from the two real results, shown only when a
                // previous run exists.
                val previous = state.previousResult
                val current = state.downloadBitsPerSecond
                if (previous != null && current != null) {
                    val delta = DeltaMath.percentChange(current, previous.downloadBps * 8.0)
                    if (delta != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        ComparisonChip(delta = delta, palette = palette)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                ResultRow(stringResource(R.string.result_server), state.server)
                ResultRow(
                    stringResource(R.string.result_jitter),
                    state.jitterMs?.let { "$it ${stringResource(R.string.unit_ms)}" } ?: "—"
                )
                ResultRow(
                    stringResource(R.string.result_download),
                    state.downloadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                )
                ResultRow(
                    stringResource(R.string.result_upload),
                    state.uploadBitsPerSecond?.let { SpeedFormatter.formatBits(it) } ?: "—"
                )
                if (state.uploadAckCode > 0) {
                    ResultRow(
                        stringResource(R.string.result_upload_check),
                        stringResource(R.string.result_upload_check_value, state.uploadAckCode)
                    )
                }
                if (state.uploadBitsPerSecond != null) {
                    ResultRow(
                        stringResource(R.string.result_upload_window),
                        stringResource(
                            if (state.uploadAcknowledgedWindow) R.string.result_upload_window_acknowledged
                            else R.string.result_upload_window_send_side
                        )
                    )
                }
                ResultRow(stringResource(R.string.result_completed), formatTimestamp(state.finishedAtMillis))
                if (state.usedFallback) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.test_fallback_note),
                        fontSize = 10.sp,
                        color = secondary().copy(alpha = 0.85f)
                    )
                }
            }
        }
    }

    if (state.phase == SpeedTestPhase.ERROR) {
        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = topPadding)) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
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
                            stringResource(R.string.test_stopped)
                        } else {
                            stringResource(R.string.test_error_title_failed)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = state.error ?: stringResource(R.string.test_error_hint),
                        fontSize = 11.sp,
                        color = secondary()
                    )
                }
            }
        }
    }
}

/** "↑ 12 % vs last test" — a real comparison of two real runs. */
@Composable
private fun ComparisonChip(delta: Int, palette: GaugePalette) {
    val label = when {
        delta > 0 -> stringResource(R.string.delta_vs_last_test_up, delta)
        delta < 0 -> stringResource(R.string.delta_vs_last_test_down, -delta)
        else -> stringResource(R.string.delta_vs_last_test_same)
    }
    val tint = when {
        delta > 0 -> palette.live
        delta < 0 -> AccentWarning
        else -> secondary()
    }
    GlassChip(modifier = Modifier.testTag("speed_test_delta_chip")) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun ActionButton(state: SpeedTestState, viewModel: SpeedTestViewModel, topPadding: androidx.compose.ui.unit.Dp) {
    Button(
        onClick = {
            if (state.isRunning) viewModel.cancelTest() else viewModel.requestTest()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
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
}

@Composable
private fun FootNote() {
    Text(
        text = stringResource(R.string.test_footnote, SpeedTestServers.CLOUDFLARE.host),
        fontSize = 10.sp,
        color = secondary().copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
    )
}

@Composable
private fun HistorySection(history: List<SpeedTestResult>, onClear: () -> Unit, onExport: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.test_history_title),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            fontFamily = FontFamily.Monospace,
            color = secondary()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val exportLabel = stringResource(R.string.a11y_export_test_history)
            Text(
                text = stringResource(R.string.action_export),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(glassColor)
                    .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = exportLabel, role = Role.Button) { onExport() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("speed_test_export_history")
            )
            val clearLabel = stringResource(R.string.a11y_clear_test_history)
            Text(
                text = stringResource(R.string.action_clear),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = AccentDanger,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(glassColor)
                    .border(1.dp, glassBorder, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = clearLabel, role = Role.Button) { onClear() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag("speed_test_clear_history")
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

@Composable
private fun MeteredConfirmationDialog(
    estimateMb: Int,
    transport: Transport,
    dataSaverOn: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val transportName = when (transport) {
        Transport.MOBILE -> stringResource(R.string.test_confirm_transport_mobile)
        Transport.WIFI -> stringResource(R.string.test_confirm_transport_wifi)
        Transport.VPN -> stringResource(R.string.test_confirm_transport_vpn)
        else -> stringResource(R.string.test_confirm_transport_other)
    }
    val body = buildString {
        append(
            stringResource(
                R.string.test_confirm_body,
                transportName,
                estimateMb,
                (SpeedTestEngine.DEFAULT_DOWNLOAD_MS / 1000L).toInt(),
                (SpeedTestEngine.DEFAULT_UPLOAD_MS / 1000L).toInt()
            )
        )
        if (dataSaverOn) {
            append("\n\n")
            append(stringResource(R.string.test_confirm_data_saver))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_confirm_title, estimateMb), fontWeight = FontWeight.Bold) },
        text = { Text(body, color = secondary()) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("speed_test_confirm_run")) {
                Text(
                    stringResource(R.string.action_run_test),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("speed_test_confirm_cancel")) {
                Text(stringResource(R.string.action_cancel), color = secondary())
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

// ============================== GAUGE ==============================

/** Accent used by the gauge/needle for the current phase. */
private fun gaugeAccent(state: SpeedTestState, palette: GaugePalette): Color = when (state.phase) {
    SpeedTestPhase.PING -> AccentWarning
    SpeedTestPhase.UPLOAD -> palette.up
    SpeedTestPhase.DONE -> palette.live
    SpeedTestPhase.ERROR -> AccentDanger
    else -> palette.down
}

@Composable
private fun phaseLabel(phase: SpeedTestPhase, state: SpeedTestState): String = when (phase) {
    SpeedTestPhase.IDLE -> stringResource(R.string.test_phase_idle)
    SpeedTestPhase.PING -> stringResource(R.string.test_phase_ping, state.server.uppercase(Locale.ROOT))
    SpeedTestPhase.DOWNLOAD -> stringResource(R.string.test_phase_download)
    SpeedTestPhase.UPLOAD -> stringResource(R.string.test_phase_upload)
    SpeedTestPhase.DONE -> stringResource(R.string.test_phase_done)
    SpeedTestPhase.ERROR -> if (state.aborted) {
        stringResource(R.string.test_phase_stopped)
    } else {
        stringResource(R.string.test_phase_failed)
    }
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
 * actually observed — and from the previous result, so its ghost needle always fits on
 * the dial — never from a made-up "max", so a 30 Mbit/s line and a 600 Mbit/s line both
 * get a readable dial.
 */
@Composable
private fun SpeedGauge(state: SpeedTestState, palette: GaugePalette) {
    val accent = gaugeAccent(state, palette)

    // Highest real value seen during this run — the dial's ceiling is derived from it.
    var observedMaxMbps by remember(state.startedAtMillis) { mutableFloatStateOf(0f) }
    val currentMbps = (state.gaugeBitsPerSecond / 1_000_000.0).toFloat()
    LaunchedEffect(currentMbps) {
        if (currentMbps > observedMaxMbps) observedMaxMbps = currentMbps
    }
    val ghostMbps = state.ghostBitsPerSecond?.let { (it / 1_000_000.0).toFloat() }
    val ceilingMbps = niceCeilingMbps(max(observedMaxMbps, ghostMbps ?: 0f))

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
    val showGhost = ghostMbps != null && state.phase != SpeedTestPhase.PING
    val ghostFraction by animateFloatAsState(
        targetValue = if (showGhost) ((ghostMbps ?: 0f) / ceilingMbps).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "speed_test_ghost_needle"
    )

    val trackColor = glassBorder.copy(alpha = 0.65f)
    val tickColor = secondary().copy(alpha = 0.55f)
    val tickMajorColor = secondary().copy(alpha = 0.85f)
    val ghostColor = secondary().copy(alpha = 0.45f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        color = secondary()
    )

    val latency = state.latencyMs
    val (number, unit) = if (state.phase == SpeedTestPhase.PING && latency != null) {
        latency.toString() to stringResource(R.string.unit_ms)
    } else {
        SpeedFormatter.splitBits(state.gaugeBitsPerSecond)
    }
    val scaleText = if (state.phase == SpeedTestPhase.PING) {
        stringResource(R.string.gauge_latency)
    } else {
        stringResource(R.string.gauge_scale, ceilingLabel(ceilingMbps))
    }
    val gaugeDescription = stringResource(
        R.string.a11y_gauge,
        number,
        unit,
        phaseLabel(state.phase, state)
    )

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

            // Ghost needle: where the previous run landed (same metric as the live needle).
            if (showGhost) {
                val ghostAngle = Math.toRadians((startAngle + sweep * ghostFraction).toDouble())
                val ghostLength = radius * 0.66f
                val ghostStart = radius * 0.22f
                drawLine(
                    color = ghostColor,
                    start = Offset(
                        center.x + ghostStart * cos(ghostAngle).toFloat(),
                        center.y + ghostStart * sin(ghostAngle).toFloat()
                    ),
                    end = Offset(
                        center.x + ghostLength * cos(ghostAngle).toFloat(),
                        center.y + ghostLength * sin(ghostAngle).toFloat()
                    ),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
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
                modifier = Modifier
                    .testTag("speed_test_gauge_number")
                    // The dial itself is decoration; the reading carries the description.
                    .semantics { contentDescription = gaugeDescription }
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
                text = scaleText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp,
                color = secondary().copy(alpha = 0.7f)
            )
            AnimatedVisibility(visible = showGhost) {
                Text(
                    text = stringResource(
                        R.string.gauge_ghost_legend,
                        SpeedFormatter.formatBits(state.ghostBitsPerSecond ?: 0.0)
                    ),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp,
                    color = ghostColor,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .testTag("speed_test_ghost_legend")
                )
            }
        }
    }
}

/**
 * The smallest "round" dial ceiling that fits [observedMbps] with headroom, so the needle
 * spends most of the run in the readable part of the dial instead of pinned at the edge.
 */
internal fun niceCeilingMbps(observedMbps: Float): Float {
    val candidates = floatArrayOf(10f, 25f, 50f, 100f, 250f, 500f, 1000f, 2500f)
    // The smallest calibrated step that holds the observed maximum (10 Mbps floor). No
    // extra headroom factor: 95 Mbps on a 100 dial reads as "fast", on a 250 dial as
    // "weak" — and the dial re-scales the moment a reading exceeds the step anyway.
    val target = observedMbps.coerceAtLeast(10f)
    return candidates.firstOrNull { it >= target } ?: candidates.last()
}

/** Short label for the dial ceiling — whole numbers only, no stray ".0" (`1000`, `250`). */
private fun ceilingLabel(ceilingMbps: Float): String = ceilingMbps.roundToLong().toString()

// ============================== SHARING ==============================

/** Plain `ACTION_SEND` text — no SDK, no image, no account. */
private fun shareResult(context: Context, state: SpeedTestState) {
    val download = state.downloadBitsPerSecond ?: 0.0
    val upload = state.uploadBitsPerSecond ?: 0.0
    val ping = state.latencyMs ?: 0L
    val jitter = state.jitterMs ?: 0L
    val text = context.getString(
        R.string.share_result_text,
        SpeedFormatter.formatBits(download),
        SpeedFormatter.formatBits(upload),
        ping,
        jitter,
        state.server,
        formatTimestamp(state.finishedAtMillis)
    )
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_result_chooser)))
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
    isLatency: Boolean,
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
    if (isLatency) {
        value = if (latencyMs == null) "—" else animatedLatency.roundToLong().toString()
        unit = unitForLatency
    } else {
        val split = SpeedFormatter.splitBits(animatedBits.toDouble())
        value = if (bitsPerSecond == null) "—" else split.first
        unit = split.second
    }

    val valueDescription = "$label $value $unit"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
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
                modifier = Modifier
                    .testTag(testTag)
                    .semantics { contentDescription = valueDescription }
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

    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
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
                        contentDescription = stringResource(R.string.label_download),
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
                        contentDescription = stringResource(R.string.label_upload),
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
                text = "${result.latencyMs} ${stringResource(R.string.unit_ms)}",
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
