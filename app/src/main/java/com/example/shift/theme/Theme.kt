package com.example.shift.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

val ShiftColorScheme = lightColorScheme(
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
    surfaceVariant       = surfaceContainer,
    onSurfaceVariant     = onSurfaceVariant,
    outline              = outline,
    outlineVariant       = outlineVariant,
    surfaceContainer     = surfaceContainer,
    surfaceContainerHigh = surfaceContainerHigh,
    surfaceContainerHighest = surfaceContainerHighest
)

val ShiftShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
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
