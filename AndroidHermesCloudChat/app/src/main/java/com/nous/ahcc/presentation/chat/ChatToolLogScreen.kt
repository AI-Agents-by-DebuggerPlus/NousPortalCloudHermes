package com.nous.ahcc.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ChatToolLogScreen(
    viewModel: HermesChatViewModel,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        text = viewModel.toolLogText()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F2A))
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFFE8F4F3)
                )
            }
            Text(
                "Журнал команд",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFE8F4F3),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    viewModel.clearToolLog()
                    text = ""
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A4550),
                    contentColor = Color(0xFFE8F4F3)
                )
            ) {
                Text("Очистить")
            }
        }
        Text(
            text = "Служебные команды Hermes. В чат и в озвучку они не попадают.",
            color = Color(0xFF8AA8A6),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = text.ifBlank { "Журнал пуст" },
            color = Color(0xFFE8F4F3),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )
    }
}
