package com.example.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.SpeedDown
import com.example.ui.theme.SpeedDownDark
import com.example.ui.theme.SpeedUp
import com.example.ui.theme.SpeedUpDark
import com.example.ui.theme.isDarkModeActive

/** Secondary text color for the active mode. */
@Composable
fun secondary(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Download accent for the active mode. */
@Composable
fun dlColor(): Color = if (isDarkModeActive()) SpeedDownDark else SpeedDown

/** Upload accent for the active mode. */
@Composable
fun ulColor(): Color = if (isDarkModeActive()) SpeedUpDark else SpeedUp

/** MB value (Double) to bytes. */
fun Double.toBytes(): Long = (this * 1024.0 * 1024.0).toLong()
