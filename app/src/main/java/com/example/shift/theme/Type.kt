package com.example.shift.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.shift.R

val dmSansFamily = FontFamily(Font(R.font.dmsans))
val letteraMonoFamily = FontFamily(Font(R.font.lettera_mono_ll_regular))
val ndotFamily = FontFamily(Font(R.font.ndot57_regular))

// Custom TextStyles for Shift v4 Design Language
val ScreenTitleStyle = TextStyle(
    fontFamily = dmSansFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 30.sp,
    lineHeight = 36.sp,
    letterSpacing = (-0.5).sp,
    color = ShiftTextPrimary
)

val CardTitleStyle = TextStyle(
    fontFamily = dmSansFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp,
    color = ShiftTextPrimary
)

val MicroLabelStyle = TextStyle(
    fontFamily = letteraMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.8.sp,
    color = ShiftTextMuted
)

val StatNumeralHero = TextStyle(
    fontFamily = ndotFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 34.sp,
    lineHeight = 38.sp,
    letterSpacing = 0.sp
)

val StatNumeralLarge = TextStyle(
    fontFamily = ndotFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 28.sp,
    lineHeight = 32.sp,
    letterSpacing = 0.sp
)

val StatNumeralTile = TextStyle(
    fontFamily = ndotFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    letterSpacing = 0.sp
)

val StatNumeralSmall = TextStyle(
    fontFamily = ndotFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 20.sp,
    letterSpacing = 0.sp
)

val ShiftTypography = Typography(
    displayLarge = TextStyle(
        fontFamily    = ndotFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 57.sp,
        lineHeight    = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily    = ndotFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 45.sp,
        lineHeight    = 52.sp,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontFamily    = ndotFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 36.sp,
        lineHeight    = 44.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 30.sp,
        lineHeight    = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 26.sp,
        lineHeight    = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 22.sp,
        lineHeight    = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 18.sp,
        lineHeight    = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.SemiBold,
        fontSize      = 15.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 14.sp,
        lineHeight    = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 16.sp,
        lineHeight    = 22.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 12.sp,
        lineHeight    = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily    = dmSansFamily,
        fontWeight    = FontWeight.Medium,
        fontSize      = 14.sp,
        lineHeight    = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily    = letteraMonoFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 10.sp,
        lineHeight    = 14.sp,
        letterSpacing = 1.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily    = letteraMonoFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 9.sp,
        lineHeight    = 12.sp,
        letterSpacing = 1.5.sp,
    )
)
