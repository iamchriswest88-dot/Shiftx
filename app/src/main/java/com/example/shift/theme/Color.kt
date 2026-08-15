package com.example.shift.theme

import androidx.compose.ui.graphics.Color

// Shift v4 Nothing-OS Design System Palette Tokens
val ShiftBg = Color(0xFFEBEBE5)
val ShiftCard = Color(0xFFF7F7F2)
val ShiftCardInset = Color(0xFFEBEBE3)
val ShiftCardSelected = Color(0xFFFFFFFF)
val ShiftDarkSurface = Color(0xFF1B1B19)

val ShiftTextOnDark = Color(0xFFF2F2ED)
val ShiftTextPrimary = Color(0xFF1A1A18)
val ShiftTextSecondary = Color(0xFF5A5A54)
val ShiftTextMuted = Color(0xFF8A8A84)

val ShiftHairline = Color(0xFFC9C9C0)
val ShiftDivider = Color(0xFFD9D9D0)
val ShiftDotBorder = Color(0xFFB9B9B0)

// Accent purple — swapped from the original orange (#F0521E) at matched
// perceived lightness, so contrast against white text and the light ground
// is unchanged.
val ShiftAccent = Color(0xFFA855F7)

val FeelGoodColor = Color(0xFF7ED957)
val FeelMidColor = Color(0xFFFFC64B)
val FeelLowColor = Color(0xFFF0521E)

val ChartCtlColor = Color(0xFF1A1A18)
val ChartAtlColor = Color(0xFFF0521E)
val ElevProfileFill = Color(0xFFE3E3DB)
val ElevProfileStroke = Color(0xFF1A1A18)

/**
 * The route line on the map, matching '#C084FC' in assets/leaflet_map.html —
 * a light purple sitting one step above the solid accent, as the old cyan sat
 * apart from the orange. Shared so the elevation profile cannot drift away
 * from the route it describes.
 */
val RouteLineColor = Color(0xFFC084FC)

// Backward-compatible material color mappings
val primary = ShiftAccent
val onPrimary = Color(0xFFFFFFFF)
val primaryContainer = ShiftAccent
val onPrimaryContainer = Color(0xFFFFFFFF)
val secondaryContainer = ShiftCardInset
val onSecondaryContainer = ShiftTextPrimary
val tertiary = FeelGoodColor
val surface = ShiftBg
val surfaceContainer = ShiftCard
val surfaceContainerHigh = ShiftCardSelected
val surfaceContainerHighest = ShiftCardInset
val onSurface = ShiftTextPrimary
val onSurfaceVariant = ShiftTextSecondary
val outline = ShiftHairline
val outlineVariant = ShiftDivider
