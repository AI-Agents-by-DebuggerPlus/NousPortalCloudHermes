package com.nous.ahcc.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nous.ahcc.presentation.chat.ChatScreen
import com.nous.ahcc.presentation.chat.ChatToolLogScreen
import com.nous.ahcc.presentation.chat.FlashcardsScreen
import com.nous.ahcc.presentation.chat.HermesChatViewModel
import com.nous.ahcc.presentation.chat.TtsVoiceScreen
import com.nous.ahcc.presentation.headset.PlayTapTestScreen
import com.nous.ahcc.presentation.settings.SettingsScreen

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
    const val BluetoothTest = "bluetooth_test"
    const val VoiceTest = "voice_test"
    const val SttTest = "stt_test"
    const val TelegramLogin = "telegram_login"
    const val ToolLog = "tool_log"
    const val TtsVoices = "tts_voices"
    const val Flashcards = "flashcards"
    const val PlayTapTest = "play_tap_test"
}

@Composable
fun AhccNavHost(viewModel: HermesChatViewModel) {
    val navController = rememberNavController()
    val navEntry by navController.currentBackStackEntryAsState()
    val route = navEntry?.destination?.route
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(route) {
        if (route == Routes.PlayTapTest) viewModel.enterPlayTapTest()
        else viewModel.leavePlayTapTest()
    }
    LaunchedEffect(state.flashcardsActive, route) {
        if (route == Routes.PlayTapTest) return@LaunchedEffect
        val onCards = route == Routes.Flashcards
        if (state.flashcardsActive && !onCards) {
            navController.navigate(Routes.Flashcards) { launchSingleTop = true }
        } else if (!state.flashcardsActive && onCards) {
            navController.popBackStack()
        }
    }
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
                onOpenSttTest = { navController.navigate(Routes.SttTest) },
                onOpenTelegramLogin = { navController.navigate(Routes.TelegramLogin) },
                onOpenTtsVoices = { navController.navigate(Routes.TtsVoices) },
                onOpenPlayTapTest = { navController.navigate(Routes.PlayTapTest) },
            )
        }
        composable(Routes.Flashcards) {
            FlashcardsScreen(viewModel = viewModel)
        }
        composable(Routes.PlayTapTest) {
            PlayTapTestScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
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
        composable(Routes.SttTest) {
            com.nous.ahcc.presentation.voice.SttTestScreen(
                chatViewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
