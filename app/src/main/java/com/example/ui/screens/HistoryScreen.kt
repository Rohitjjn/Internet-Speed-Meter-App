package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.DataUsageEntity
import com.example.ui.components.AppBackground
import com.example.ui.components.AppIcon
import com.example.ui.components.GlassCard
import com.example.ui.theme.AccentWarning
import com.example.ui.theme.LiveGreen
import com.example.ui.theme.LiveGreenDark
import com.example.ui.theme.glassBorder
import com.example.ui.theme.glassColor
import com.example.ui.theme.isDarkModeActive
import com.example.ui.util.secondary
import com.example.ui.viewmodel.HistoryViewModel
import com.example.util.BytesFormatter
import com.example.util.CsvExport
import com.example.util.DateFormatter
import com.example.util.DeltaMath
import com.example.util.LimitAlerts
import com.example.util.UsageCsv
import java.util.Locale

/** Width from which the list and the monthly recap sit side by side. */
private val HistoryTwoColumnBreakpoint = 600.dp

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = viewModel()
) {
    val historyList by viewModel.historyList.collectAsState()
    val monthlyTotalBytes by viewModel.monthlyTotalBytes.collectAsState()
    val previousMonthTotalBytes by viewModel.previousMonthTotalBytes.collectAsState()
    val monthLabel by viewModel.monthLabel.collectAsState()
    val monthlyMobileBytes by viewModel.monthlyMobileBytes.collectAsState()
    val monthlyLimitBytes by viewModel.monthlyLimitBytes.collectAsState()
    val monthlyLimitLabel by viewModel.monthlyLimitLabel.collectAsState()

    var showResetConfirmation by remember { mutableStateOf(false) }
    var showPurgeConfirmation by remember { mutableStateOf(false) }

    val todayStr = remember { DateFormatter.day() }
    val isDark = isDarkModeActive()
    val todayAccent = if (isDark) LiveGreenDark else LiveGreen
    val context = LocalContext.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("history_screen_root")
    ) {
        AppBackground()
        val twoColumn = maxWidth >= HistoryTwoColumnBreakpoint

        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------- Header ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.history_eyebrow),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = secondary()
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(R.string.history_title),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (historyList.isNotEmpty()) {
                        IconButton(
                            onClick = { shareUsageCsv(context, historyList) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(glassColor)
                                .border(1.dp, glassBorder, RoundedCornerShape(10.dp))
                                .size(38.dp)
                                .testTag("history_export_csv")
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_share,
                                contentDescription = stringResource(R.string.a11y_export_history),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { showResetConfirmation = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(glassColor)
                            .border(1.dp, glassBorder, RoundedCornerShape(10.dp))
                            .size(38.dp)
                            .testTag("history_reset_today")
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_restore,
                            contentDescription = stringResource(R.string.a11y_reset_today),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(
                        onClick = { showPurgeConfirmation = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(glassColor)
                            .border(1.dp, glassBorder, RoundedCornerShape(10.dp))
                            .size(38.dp)
                            .testTag("history_purge_all")
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_delete_forever,
                            contentDescription = stringResource(R.string.a11y_clear_all_history),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(17.dp)
                        )
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

            if (historyList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("history_empty_view"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(glassColor)
                                .border(1.dp, glassBorder, RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                resId = R.drawable.ic_history,
                                contentDescription = null,
                                tint = secondary(),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.history_empty_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.history_empty_body),
                            fontSize = 12.sp,
                            color = secondary()
                        )
                    }
                }
            } else if (twoColumn) {
                // Wide layouts: the day list on the left, the monthly recap as a side panel.
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("history_lazy_column"),
                        contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = historyList,
                            key = { it.date }
                        ) { item ->
                            HistoryRowItem(item = item, todayStr = todayStr, todayAccent = todayAccent)
                        }
                    }
                    Box(modifier = Modifier.width(320.dp).padding(end = 8.dp)) {
                        PinnedMonthlySummaryCard(
                            totalBytes = monthlyTotalBytes,
                            previousMonthBytes = previousMonthTotalBytes,
                            monthLabel = monthLabel,
                            horizontalPadding = 12.dp,
                            mobileBytes = monthlyMobileBytes,
                            mobileLimitBytes = monthlyLimitBytes,
                            mobileLimitLabel = monthlyLimitLabel
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("history_lazy_column"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = historyList,
                        key = { it.date }
                    ) { item ->
                        HistoryRowItem(item = item, todayStr = todayStr, todayAccent = todayAccent)
                    }
                }
            }

            // ---------------- Pinned monthly summary ----------------
            if (!twoColumn || historyList.isEmpty()) {
                PinnedMonthlySummaryCard(
                    totalBytes = monthlyTotalBytes,
                    previousMonthBytes = previousMonthTotalBytes,
                    monthLabel = monthLabel,
                    mobileBytes = monthlyMobileBytes,
                    mobileLimitBytes = monthlyLimitBytes,
                    mobileLimitLabel = monthlyLimitLabel
                )
            }
        }
    }
}

/** The stored days as a real `.csv` attachment (FileProvider from the private cache, no permission). */
private fun shareUsageCsv(context: android.content.Context, rows: List<DataUsageEntity>) {
    CsvExport.share(
        context = context,
        csv = UsageCsv.build(rows),
        fileName = CsvExport.fileName("usage"),
        subject = context.getString(R.string.export_subject),
        chooserTitle = context.getString(R.string.export_chooser)
    )
}

/**
 * One stored day. Tapping the card opens the per-day drill-down: download and upload per
 * network, straight from the four stored counters (nothing derived, nothing estimated).
 */
@Composable
fun HistoryRowItem(
    item: DataUsageEntity,
    todayStr: String,
    todayAccent: androidx.compose.ui.graphics.Color
) {
    val dateDisplay = DateFormatter.readable(item.date, todayStr, stringResource(R.string.label_today_word))
    val isToday = item.date == todayStr
    var expanded by rememberSaveable(item.date) { mutableStateOf(false) }
    val expandedState = stringResource(R.string.a11y_collapse)
    val collapsedState = stringResource(R.string.a11y_expand)

    GlassCard(modifier = Modifier.fillMaxWidth().testTag("history_item_card_${item.date}")) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) expandedState else collapsedState
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedState else collapsedState }
                .padding(16.dp)
                .testTag("history_item_toggle_${item.date}")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(glassColor),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(
                            resId = R.drawable.ic_calendar,
                            contentDescription = null,
                            tint = if (isToday) todayAccent else secondary(),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    Text(
                        text = dateDisplay,
                        fontSize = 14.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = BytesFormatter.format(item.total),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("history_sum_${item.date}")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AppIcon(
                        resId = R.drawable.ic_cellular,
                        contentDescription = stringResource(R.string.network_mobile),
                        tint = secondary(),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = BytesFormatter.format(item.mobileTotal),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = secondary(),
                        modifier = Modifier.testTag("history_mobile_${item.date}")
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    AppIcon(
                        resId = R.drawable.ic_wifi,
                        contentDescription = stringResource(R.string.network_wifi),
                        tint = secondary(),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = BytesFormatter.format(item.wifiTotal),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = secondary(),
                        modifier = Modifier.testTag("history_wifi_${item.date}")
                    )
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        animationSpec = tween(200),
                        label = "history_chevron"
                    )
                    AppIcon(
                        resId = R.drawable.ic_expand_more,
                        contentDescription = null,
                        tint = secondary(),
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(chevronRotation)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120))
            ) {
                Column(modifier = Modifier.padding(top = 12.dp).testTag("history_detail_${item.date}")) {
                    HorizontalDivider(color = glassBorder.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DayDirectionColumn(
                            modifier = Modifier.weight(1f),
                            iconRes = R.drawable.ic_cellular,
                            title = stringResource(R.string.network_mobile),
                            rx = item.mobileRx,
                            tx = item.mobileTx
                        )
                        DayDirectionColumn(
                            modifier = Modifier.weight(1f),
                            iconRes = R.drawable.ic_wifi,
                            title = stringResource(R.string.network_wifi),
                            rx = item.wifiRx,
                            tx = item.wifiTx
                        )
                    }
                }
            }
        }
    }
}

/** Download / upload of one network for one day, from the stored counters. */
@Composable
private fun DayDirectionColumn(modifier: Modifier, iconRes: Int, title: String, rx: Long, tx: Long) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AppIcon(resId = iconRes, contentDescription = null, tint = secondary(), modifier = Modifier.size(11.dp))
            Text(
                text = title.uppercase(Locale.getDefault()),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = FontFamily.Monospace,
                color = secondary()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        DirectionLine(iconRes = R.drawable.ic_arrow_down, label = stringResource(R.string.label_download), bytes = rx)
        Spacer(modifier = Modifier.height(3.dp))
        DirectionLine(iconRes = R.drawable.ic_arrow_up, label = stringResource(R.string.label_upload), bytes = tx)
    }
}

@Composable
private fun DirectionLine(iconRes: Int, label: String, bytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AppIcon(resId = iconRes, contentDescription = label, tint = secondary(), modifier = Modifier.size(10.dp))
            Text(text = label.lowercase(Locale.getDefault()), fontSize = 10.sp, color = secondary())
        }
        Text(
            text = BytesFormatter.format(bytes),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/**
 * Pinned recap: this month's real total plus how it compares with the previous month.
 * The delta is only shown when the previous month has rows — otherwise the app would be
 * comparing against data it never measured.
 */
@Composable
fun PinnedMonthlySummaryCard(
    totalBytes: Long,
    previousMonthBytes: Long = 0L,
    monthLabel: String = "",
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
    mobileBytes: Long = 0L,
    mobileLimitBytes: Long = 0L,
    mobileLimitLabel: String = ""
) {
    val isDark = isDarkModeActive()
    val cardColor = if (isDark) Color(0xFFF2F4F8) else Color(0xFF15181D)
    val onCard = if (isDark) Color(0xFF15181D) else Color(0xFFF5F6F8)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = horizontalPadding, vertical = 14.dp)
            .testTag("pinned_summary_card")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(20.dp),
                    ambientColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.18f),
                    spotColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.22f)
                )
                .clip(RoundedCornerShape(20.dp))
                .background(cardColor)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (monthLabel.isBlank()) {
                                stringResource(R.string.history_this_month)
                            } else {
                                stringResource(R.string.history_this_month_named, monthLabel.uppercase(Locale.getDefault()))
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            color = onCard.copy(alpha = 0.55f)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.history_monthly_total),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = onCard
                        )
                        if (previousMonthBytes > 0L) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = monthDeltaLabel(totalBytes, previousMonthBytes),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = onCard.copy(alpha = 0.75f),
                                modifier = Modifier.testTag("month_delta_text")
                            )
                        }
                    }
                    Text(
                        text = BytesFormatter.format(totalBytes),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = onCard,
                        modifier = Modifier.testTag("monthly_total_text")
                    )
                }
                // Optional monthly mobile limit: progress of the real month-to-date mobile bytes.
                // Warn-only — the bar is information, never a gate.
                val progress = LimitAlerts.progress(mobileBytes, mobileLimitBytes)
                if (progress != null) {
                    val percent = (progress * 100).toInt()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp)
                            .testTag("monthly_limit_progress")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.history_monthly_limit_line,
                                    BytesFormatter.format(mobileBytes),
                                    mobileLimitLabel
                                ),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = onCard.copy(alpha = 0.75f)
                            )
                            Text(
                                text = "$percent%",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (percent >= 90) AccentWarning else onCard.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = if (percent >= 90) AccentWarning else onCard,
                            trackColor = onCard.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * "↓ 12% vs last month" / "↑ 30% vs last month" / "same as last month".
 * Percentages are computed from the two real month totals only ([DeltaMath]).
 */
@Composable
internal fun monthDeltaLabel(current: Long, previous: Long): String {
    if (previous <= 0L) return ""
    if (current == 0L) return stringResource(R.string.delta_vs_last_month_none)
    val delta = DeltaMath.percentChange(current.toDouble(), previous.toDouble()) ?: return ""
    return when {
        delta == 0 -> stringResource(R.string.delta_vs_last_month_same)
        delta > 0 -> stringResource(R.string.delta_vs_last_month_up, delta)
        else -> stringResource(R.string.delta_vs_last_month_down, -delta)
    }
}
