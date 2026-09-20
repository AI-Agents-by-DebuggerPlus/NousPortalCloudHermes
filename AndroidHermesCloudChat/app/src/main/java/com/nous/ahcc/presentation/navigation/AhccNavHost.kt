package com.nous.ahcc.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nous.ahcc.presentation.chat.ChatScreen
import com.nous.ahcc.presentation.chat.HermesChatViewModel
import com.nous.ahcc.presentation.settings.SettingsScreen

object Routes {
    const val Chat = "chat"
    const val Settings = "settings"
    const val BluetoothTest = "bluetooth_test"
}

@Composable
fun AhccNavHost(viewModel: HermesChatViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.Chat) {
        composable(Routes.Chat) {
            ChatScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onOpenBluetoothTest = { navController.navigate(Routes.BluetoothTest) }
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenBluetoothTest = { navController.navigate(Routes.BluetoothTest) }
            )
        }
        composable(Routes.BluetoothTest) {
            com.nous.ahcc.presentation.bluetooth.BluetoothTestScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
