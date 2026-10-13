package com.example.ui.components

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/**
 * Renders one of the app's bundled vector drawables (replaces the heavy
 * material-icons-extended dependency — only ~24 tiny icons are shipped).
 */
@Composable
fun AppIcon(
    resId: Int,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(id = resId),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}
