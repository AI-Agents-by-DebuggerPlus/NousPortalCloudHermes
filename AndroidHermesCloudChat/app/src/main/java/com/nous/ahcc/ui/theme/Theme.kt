package com.nous.ahcc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF0B1F2A)
private val DeepTeal = Color(0xFF123D45)
private val Accent = Color(0xFF1FA6A0)
private val AccentSoft = Color(0xFFB8E4E0)
private val CreamText = Color(0xFFE8F4F3)
private val Muted = Color(0xFF8AA8A6)
private val Danger = Color(0xFFE07070)
private val SurfaceLight = Color(0xFFF3F7F6)
private val OnLight = Color(0xFF102428)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Navy,
    secondary = AccentSoft,
    onSecondary = Navy,
    background = Navy,
    onBackground = CreamText,
    surface = DeepTeal,
    onSurface = CreamText,
    surfaceVariant = Color(0xFF1A4550),
    onSurfaceVariant = Muted,
    error = Danger,
    onError = CreamText,
    outline = Color(0xFF3A6A72)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E7C78),
    onPrimary = Color.White,
    secondary = DeepTeal,
    onSecondary = CreamText,
    background = SurfaceLight,
    onBackground = OnLight,
    surface = Color.White,
    onSurface = OnLight,
    surfaceVariant = Color(0xFFD9E8E6),
    onSurfaceVariant = Color(0xFF3A5554),
    error = Danger,
    onError = Color.White,
    outline = Color(0xFF8AA8A6)
)

private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

private val AhccTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = Display,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp
    )
)

@Composable
fun AhccTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AhccTypography,
        content = content
    )
}
