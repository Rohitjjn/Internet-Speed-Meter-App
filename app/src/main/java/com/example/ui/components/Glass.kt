package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.DarkGlowBlue
import com.example.ui.theme.DarkGlowMint
import com.example.ui.theme.DarkGlowViolet
import com.example.ui.theme.LightGlowBlue
import com.example.ui.theme.LightGlowMint
import com.example.ui.theme.LightGlowViolet
import com.example.ui.theme.glassBorder
import com.example.ui.theme.glassColor
import com.example.ui.theme.glassHighlight

/**
 * Ambient background: a soft base plus three large colored glows.
 * These glows are what make the translucent [GlassCard] surfaces read as glass
 * (the ChatGPT-style "something living behind the frosted surface" effect).
 * Pure static gradient draws — essentially free at runtime.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val blue = if (isDarkModeActive()) DarkGlowBlue else LightGlowBlue
        val violet = if (isSystemInDarkTheme()) DarkGlowViolet else LightGlowViolet
        val mint = if (isSystemInDarkTheme()) DarkGlowMint else LightGlowMint

        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-220).dp, y = (-260).dp)
                .size(760.dp)
                .background(
                    Brush.radialGradient(listOf(blue.copy(alpha = 0.9f), blue.copy(alpha = 0f)))
                )
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 140.dp, y = 160.dp)
                .size(820.dp)
                .background(
                    Brush.radialGradient(listOf(violet.copy(alpha = 0.85f), violet.copy(alpha = 0f)))
                )
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 200.dp, y = (-180).dp)
                .size(640.dp)
                .background(
                    Brush.radialGradient(listOf(mint.copy(alpha = 0.7f), mint.copy(alpha = 0f)))
                )
        )
    }
}

/**
 * Frosted glass card: translucent fill over the ambient glows, 1px border,
 * soft shadow and a subtle top light-catch — the core premium surface.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    shadowElevation: Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.07f),
                spotColor = Color.Black.copy(alpha = 0.09f)
            )
            .clip(shape)
            .background(glassColor)
            .border(1.dp, glassBorder, shape)
            .drawWithContent {
                drawContent()
                // Subtle lighter strip along the top edge — the "glass catches light" cue.
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(glassHighlight, glassHighlight.copy(alpha = 0f)),
                        startY = 0f,
                        endY = 26.dp.toPx()
                    ),
                    alpha = 0.9f
                )
            },
        content = content
    )
}

/** A small glass "chip" (pill) for badges and status indicators. */
@Composable
fun GlassChip(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(glassColor)
            .border(1.dp, glassBorder, RoundedCornerShape(100.dp)),
        content = content
    )
}
