package com.nous.ahcc

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        installKeyboardAwareBack()

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

    /**
     * Esc/Back from Android Studio hardware keyboard often maps to BACK.
     * First press must only dismiss IME / clear the text-field focus — not leave the app.
     */
    private fun installKeyboardAwareBack() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val root = window.decorView
                    val imeVisible = ViewCompat.getRootWindowInsets(root)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    val focused = currentFocus
                    val editing = focused != null && focused.onCheckIsTextEditor()
                    if (imeVisible || editing || focused != null) {
                        focused?.clearFocus()
                        val imm = getSystemService(InputMethodManager::class.java)
                        imm?.hideSoftInputFromWindow(root.windowToken, 0)
                        return
                    }
                    isEnabled = false
                    try {
                        onBackPressedDispatcher.onBackPressed()
                    } finally {
                        isEnabled = true
                    }
                }
            },
        )
    }

    override fun onDestroy() {
        greeting?.shutdown()
        greeting = null
        super.onDestroy()
    }
}
