package com.bttest.v1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bttest.v1.ui.BluetoothTestScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFF81C784),
                    background = Color(0xFF0B1F2A),
                    surface = Color(0xFF122B38),
                    onBackground = Color(0xFFE8F1F5),
                    onSurface = Color(0xFFE8F1F5)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BluetoothTestScreen(
                        versionLabel = BuildConfig.VERSION_LABEL,
                        onBack = null
                    )
                }
            }
        }
    }
}
