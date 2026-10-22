package com.nous.ahcc.desktop.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** Telegram Desktop–inspired dark palette (light text on dark). */
object TgTheme {
    val ChatBg = Color(0xFF0E1621)
    val Panel = Color(0xFF17212B)
    val Incoming = Color(0xFF182533)
    val Outgoing = Color(0xFF2B5278)
    val Text = Color(0xFFF5F5F5)
    val Secondary = Color(0xFF8A9BB0)
    val Accent = Color(0xFF2AABEE)
    val AccentSoft = Color(0xFF5288C1)
    val CodeBg = Color(0xFF151E27)
    val CodeHeader = Color(0xFF8774E1)
    val Border = Color(0xFF0B121A)
    val Danger = Color(0xFFE53935)
    val Ok = Color(0xFF4FAE4E)
    val Warn = Color(0xFFF2C94C)
    val TitleBar = Color(0xFF17212B)
    /** Main window title bar — a shade lighter/bluer so the hub reads apart from agent windows. */
    val TitleBarMain = Color(0xFF1B2A3A)
    val InputField = Color(0xFF242F3D)

    fun colorScheme() = darkColorScheme(
        primary = Accent,
        onPrimary = Text,
        secondary = AccentSoft,
        onSecondary = Text,
        background = ChatBg,
        onBackground = Text,
        surface = Panel,
        onSurface = Text,
        surfaceVariant = Incoming,
        onSurfaceVariant = Secondary,
        error = Danger,
        onError = Text,
        outline = Border,
    )
}
