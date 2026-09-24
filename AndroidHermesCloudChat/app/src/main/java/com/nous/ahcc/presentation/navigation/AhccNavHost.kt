package com.nous.ahcc.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nous.ahcc.presentation.chat.ChatScreen
import com.nous.ahcc.presentation.chat.ChatToolLogScreen
import com.nous.ahcc.presentation.chat.HermesChatViewModel
import com.nous.ahcc.presentation.chat.TtsVoiceScreen
import com.nous.ahcc.presentation.settings.SettingsScreen

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
    const val BluetoothTest = "bluetooth_test"
    const val VoiceTest = "voice_test"
    const val TelegramLogin = "telegram_login"
    const val ToolLog = "tool_log"
    const val TtsVoices = "tts_voices"
}

@Composable
fun AhccNavHost(viewModel: HermesChatViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Chat) {
        composable(Routes.Chat) {
            ChatScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenBluetoothTest = { navController.navigate(Routes.BluetoothTest) },
                onOpenToolLog = { navController.navigate(Routes.ToolLog) }
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenBluetoothTest = { navController.navigate(Routes.BluetoothTest) },
                onOpenVoiceTest = { navController.navigate(Routes.VoiceTest) },
                onOpenTelegramLogin = { navController.navigate(Routes.TelegramLogin) },
                onOpenTtsVoices = { navController.navigate(Routes.TtsVoices) }
            )
        }
        composable(Routes.TtsVoices) {
            TtsVoiceScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ToolLog) {
            ChatToolLogScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.TelegramLogin) {
            com.nous.ahcc.presentation.telegram.TelegramLoginScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.BluetoothTest) {
            com.nous.ahcc.presentation.bluetooth.BluetoothTestScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.VoiceTest) {
            com.nous.ahcc.presentation.voice.VoiceTestScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
