package com.nous.ahcc.desktop.telegram

sealed class DesktopTelegramAuth {
    data object Starting : DesktopTelegramAuth()
    data object NeedPhone : DesktopTelegramAuth()
    data object NeedCode : DesktopTelegramAuth()
    data class NeedPassword(val hint: String) : DesktopTelegramAuth()
    data object Ready : DesktopTelegramAuth()
    data class Failed(val message: String) : DesktopTelegramAuth()
}
