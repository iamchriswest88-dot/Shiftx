package com.example.shift.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

val ShiftColorScheme = darkColorScheme(
    primary              = primary,
    onPrimary            = onPrimary,
    primaryContainer     = primaryContainer,
    onPrimaryContainer   = onPrimaryContainer,
    secondaryContainer   = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary             = tertiary,
    background           = surface,
    onBackground         = onSurface,
    surface              = surface,
    onSurface            = onSurface,
    surfaceVariant       = surfaceContainerHigh,
    onSurfaceVariant     = onSurfaceVariant,
    outline              = outline,
    outlineVariant       = outlineVariant,
    surfaceContainer     = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest
)

val ShiftShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ShiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShiftColorScheme,
        typography  = ShiftTypography,
        shapes      = ShiftShapes,
        content     = content
    )
}
