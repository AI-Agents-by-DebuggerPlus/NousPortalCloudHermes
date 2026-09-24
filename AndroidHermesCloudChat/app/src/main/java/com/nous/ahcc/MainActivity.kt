package com.nous.ahcc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nous.ahcc.media.StartupGreeting
import com.nous.ahcc.presentation.chat.HermesChatViewModel
import com.nous.ahcc.presentation.chat.HermesChatViewModelFactory
import com.nous.ahcc.presentation.navigation.AhccNavHost
import com.nous.ahcc.ui.theme.AhccTheme

class MainActivity : ComponentActivity() {
    private var greeting: StartupGreeting? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            greeting = StartupGreeting(this)
        }
        enableEdgeToEdge()

        val app = application as AhccApp
        setContent {
            AhccTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: HermesChatViewModel = viewModel(
                        factory = HermesChatViewModelFactory(
                            application = app,
                            preferences = app.preferences,
                            chatGateway = app.chatGateway
                        )
                    )
                    AhccNavHost(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        greeting?.shutdown()
        greeting = null
        super.onDestroy()
    }
}
