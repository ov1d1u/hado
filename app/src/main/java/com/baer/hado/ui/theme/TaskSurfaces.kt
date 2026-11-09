package com.baer.hado.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Google Tasks style surfaces: a tinted page with lighter (or, in dark mode, raised) cards on top. */
object TaskSurfaces {
    val cardCorner = 20.dp
    val cardShape = RoundedCornerShape(cardCorner)
    val cardTopShape = RoundedCornerShape(topStart = cardCorner, topEnd = cardCorner)
    val cardBottomShape = RoundedCornerShape(bottomStart = cardCorner, bottomEnd = cardCorner)
    val cardHorizontalMargin = 12.dp

    private val isLight: Boolean
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    val page: Color
        @Composable @ReadOnlyComposable
        get() = if (isLight) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.surface

    val card: Color
        @Composable @ReadOnlyComposable
        get() = if (isLight) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.surfaceContainerHigh
}
