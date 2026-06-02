package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.services.SpeedMeterService
import com.example.ui.theme.borderColor
import com.example.ui.theme.glassColor
import com.example.ui.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel()
) {
    val isAddOnActive by viewModel.isAddOnActive.collectAsState()
    val mobileUsage by viewModel.mobileDataState.collectAsState()
    val wifiUsage by viewModel.wifiDataState.collectAsState()
    val addOnUsage by viewModel.addOnUsage.collectAsState()
    val totalUsage by viewModel.totalUsage.collectAsState()
    val showDialog by viewModel.showConfirmationDialog.collectAsState()
    val showResetDialog by viewModel.showResetConfirmationDialog.collectAsState()

    // Smooth scrollable dynamic surface container
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("dashboard_screen_root")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Elegant Premium Header Info Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "Overview",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Reset Button & Active Pulse Dot Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.requestResetTodayUsage() },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.glassColor)
                            .size(32.dp)
                            .testTag("dashboard_reset_today")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Reset Today",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .background(MaterialTheme.glassColor)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(if (isAddOnActive) Color(0xFFFF3B30) else Color(0xFF34C759))
                        )
                        Text(
                            text = if (isAddOnActive) "ADD-ON LIVE" else "MONITORING",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Live Speed Indicator
            LiveSpeedIndicatorCard()

            // Card 1: Mobile Data
            PremiumDataCard(
                title = "Mobile Data",
                icon = Icons.Default.SignalCellularAlt,
                usageMb = mobileUsage.total,
                downloadMb = mobileUsage.download,
                uploadMb = mobileUsage.upload,
                isPaused = isAddOnActive,
                cardTag = "mobile_data_card"
            )

            // Card 2: WiFi Data
            PremiumDataCard(
                title = "WiFi Connection",
                icon = Icons.Default.Wifi,
                usageMb = wifiUsage.total,
                downloadMb = wifiUsage.download,
                uploadMb = wifiUsage.upload,
                isPaused = false,
                cardTag = "wifi_data_card"
            )

            // Card 3: Data Add-On Engine (CRITICAL LOGIC)
            PremiumAddOnEngineCard(
                isAddOnActive = isAddOnActive,
                usageMb = addOnUsage.total,
                downloadMb = addOnUsage.download,
                uploadMb = addOnUsage.upload,
                onStartClick = { viewModel.requestStartTracking() },
                onStopClick = { viewModel.stopTracking() }
            )

            // Card 4: Total Usage Metric Box
            PremiumTotalCard(
                usageMb = totalUsage.total,
                downloadMb = totalUsage.download,
                uploadMb = totalUsage.upload
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Beautiful, highly polished minimalist dialog confirmation popup
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissDialog() },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmStartTracking() },
                        modifier = Modifier.testTag("dialog_confirm_button")
                    ) {
                        Text(
                            text = "START TRACKING",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissDialog() },
                        modifier = Modifier.testTag("dialog_dismiss_button")
                    ) {
                        Text(
                            text = "CANCEL",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                title = {
                    Text(
                        text = "ADD-ON DISPATCH",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                },
                text = {
                    Text(
                        text = "Start tracking Add-On Data? This pauses your standard Mobile Data calculations to protect standard cycle records.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.borderColor, RoundedCornerShape(20.dp))
                    .testTag("add_on_alert_dialog")
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissResetDialog() },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmResetTodayUsage() },
                        modifier = Modifier.testTag("dialog_confirm_reset_button")
                    ) {
                        Text(
                            text = "RESET ALL",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissResetDialog() },
                        modifier = Modifier.testTag("dialog_dismiss_reset_button")
                    ) {
                        Text(
                            text = "CANCEL",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                title = {
                    Text(
                        text = "RESET DATA USAGE",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to reset all data usage metrics for today? This action cannot be undone.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.borderColor, RoundedCornerShape(20.dp))
                    .testTag("reset_alert_dialog")
            )
        }
    }
}

// FORMAT HELPER
fun formatUsage(mbValue: Double): String {
    return if (mbValue >= 1024.0) {
        String.format("%.2f GB", mbValue / 1024.0)
    } else {
        String.format("%.1f MB", mbValue)
    }
}

@Composable
fun PremiumDataCard(
    title: String,
    icon: ImageVector,
    usageMb: Double,
    downloadMb: Double,
    uploadMb: Double,
    isPaused: Boolean,
    cardTag: String
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (isPaused) 0.45f else 1.0f,
        animationSpec = tween(durationMillis = 350),
        label = "card_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .border(1.dp, MaterialTheme.borderColor, RoundedCornerShape(20.dp))
            .testTag(cardTag),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.glassColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // If Paused, display dynamic mini badge
                AnimatedVisibility(visible = isPaused) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PauseCircle,
                            contentDescription = "Paused Icon",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "PAUSED",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Metric
            Text(
                text = "Today's Usage",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                text = formatUsage(usageMb),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp).testTag("${cardTag}_usage_text")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Download",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatUsage(downloadMb),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Upload
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Upload",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatUsage(uploadMb),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumAddOnEngineCard(
    isAddOnActive: Boolean,
    usageMb: Double,
    downloadMb: Double,
    uploadMb: Double,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isAddOnActive) Color(0xFFFF3B30) else MaterialTheme.borderColor,
        animationSpec = tween(durationMillis = 400),
        label = "border_color"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.2.dp, animatedBorderColor, RoundedCornerShape(20.dp))
            .testTag("addon_data_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isAddOnActive) Color(0xFFFF3B30).copy(alpha = 0.1f)
                                else MaterialTheme.glassColor
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isAddOnActive) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "Data Add-On",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Premium Action Trigger Switch
                if (!isAddOnActive) {
                    Button(
                        onClick = onStartClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("addon_start_button")
                    ) {
                        Text(
                            text = "START",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                } else {
                    Button(
                        onClick = onStopClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF3B30),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("addon_stop_button")
                    ) {
                        Text(
                            text = "STOP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Metric
            Text(
                text = if (isAddOnActive) "Active Add-On Tracking" else "Add-On Inactive",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (isAddOnActive) Color(0xFFFF3B30).copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                text = if (isAddOnActive) formatUsage(usageMb) else "0 MB",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = if (isAddOnActive) Color(0xFFFF3B30) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp).testTag("addon_usage_text")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Split stats under Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Download",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isAddOnActive) formatUsage(downloadMb) else "--",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("addon_download_text")
                        )
                    }
                }

                // Upload
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Upload",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isAddOnActive) formatUsage(uploadMb) else "--",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("addon_upload_text")
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumTotalCard(
    usageMb: Double,
    downloadMb: Double,
    uploadMb: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.borderColor, RoundedCornerShape(20.dp))
            .testTag("total_data_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.glassColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DataUsage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "Total Usage",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main Metric
            Text(
                text = "Consolidated Sum",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            Text(
                text = formatUsage(usageMb),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp).testTag("total_usage_text")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Stats Split Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Download
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Download",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatUsage(downloadMb),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("total_download_text")
                        )
                    }
                }

                // Upload
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload Arrow",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Column {
                        Text(
                            text = "Upload",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = formatUsage(uploadMb),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("total_upload_text")
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveSpeedIndicatorCard() {
    val dlSpeed by SpeedMeterService.downloadSpeedFlow.collectAsState()
    val ulSpeed by SpeedMeterService.uploadSpeedFlow.collectAsState()
    val isRunning by SpeedMeterService.isServiceRunning.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.borderColor, RoundedCornerShape(20.dp))
            .testTag("live_speed_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
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
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.glassColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isRunning) Color(0xFF34C759) else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "Real-Time Speed",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(if (isRunning) Color(0xFF34C759) else Color.Gray)
                    )
                    Text(
                        text = if (isRunning) "ENGINE ACTIVE" else "ENGINE INACTIVE",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Download link",
                            tint = Color(0xFF34C759),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "DOWNLOAD",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = formatLiveSpeedValue(dlSpeed),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("live_download_speed_text")
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Upload link",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "UPLOAD",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = formatLiveSpeedValue(ulSpeed),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("live_upload_speed_text")
                    )
                }
            }
        }
    }
}

fun formatLiveSpeedValue(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        String.format("%.1f MB/s", kb / 1024.0)
    } else {
        String.format("%.1f KB/s", kb)
    }
}
