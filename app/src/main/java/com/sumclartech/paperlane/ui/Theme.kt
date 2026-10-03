package com.sumclartech.paperlane.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF263E34)
val Green = Color(0xFF355E49)
val Paper = Color(0xFFF7F6F2)
val Sage = Color(0xFFE2ECD8)
val Peach = Color(0xFFF6E1D5)
val Lavender = Color(0xFFE7E3F3)
val Sand = Color(0xFFF1EACA)
val Muted = Color(0xFF798078)
private val Light = lightColorScheme(primary = Green, onPrimary = Color.White, primaryContainer = Sage,
    onPrimaryContainer = Ink, secondary = Color(0xFF9C6248), secondaryContainer = Peach,
    background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFEDEEE6), onSurfaceVariant = Color(0xFF657066), outline = Color(0xFFA5AEA2),
    outlineVariant = Color(0xFFE0E3D9), error = Color(0xFFAD3A35))
private val Dark = darkColorScheme(primary = Color(0xFFB7D2A3), onPrimary = Ink,
    primaryContainer = Green, onPrimaryContainer = Sage, secondaryContainer = Color(0xFF674939),
    background = Color(0xFF171E1A), surface = Color(0xFF171E1A), onSurface = Color(0xFFE4EBDD),
    onBackground = Color(0xFFE4EBDD), surfaceVariant = Color(0xFF2B352D), onSurfaceVariant = Color(0xFFB6C0B3),
    outlineVariant = Color(0xFF3E483F))

@Composable fun EzTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 35.sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = .4.sp),
        ), content = content)
}
