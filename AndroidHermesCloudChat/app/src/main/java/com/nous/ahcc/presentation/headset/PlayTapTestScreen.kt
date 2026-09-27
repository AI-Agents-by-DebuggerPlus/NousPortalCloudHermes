package com.nous.ahcc.presentation.headset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.AhccApp
import com.nous.ahcc.headset.HeadsetButtonEvent
import com.nous.ahcc.headset.HeadsetMonitorService
import com.nous.ahcc.presentation.chat.HermesChatViewModel

private val Navy = Color(0xFF0B1F2A)
private val Teal = Color(0xFF1A4550)
private val Cream = Color(0xFFE8F4F3)
private val Muted = Color(0xFF8AA8A6)

@Composable
fun PlayTapTestScreen(
    viewModel: HermesChatViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as AhccApp
    val hubState by app.headsetHub.state.collectAsStateWithLifecycle()
    val log = remember { mutableStateListOf<String>() }

    val hub = app.headsetHub
    val focus = LocalFocusManager.current
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(hubState.timerRunning, hubState.timerResetAtMs) {
        if (!hubState.timerRunning) {
            seconds = 0
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = (System.currentTimeMillis() - hubState.timerResetAtMs).coerceAtLeast(0L)
            seconds = (elapsed / 1000L).toInt()
            delay(200)
        }
    }
    SideEffect {
        hub.playTestActive = true
        hub.voiceGesturesEnabled = false
    }
    DisposableEffect(Unit) {
        viewModel.enterPlayTapTest()
        HeadsetMonitorService.reassert(context)
        onDispose { }
    }

    LaunchedEffect(Unit) {
        app.headsetHub.events.collect { event ->
            log.add(0, formatTap(event))
            if (log.size > 30) log.removeAt(log.lastIndex)
        }
    }

    val last = log.firstOrNull() ?: "Ждём Play"
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Cream)
            }
            Text("Тест тройного Play", color = Cream, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Следующее нажатие в окне увеличивает кратность. После паузы зачёта серия уходит в 1×, 2× или 3×, кратность обнуляется. Агенту ничего не уходит.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TapCounter(hubState.rawPlayCount, "Все нажатия", Color(0xFFE8F4F3))
            TapCounter(hubState.multiplicityCount, "Кратность", Color(0xFFB8E4E0))
        }
        Text(
            text = "Таймер: $seconds с",
            color = Cream,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TapCounter(hubState.singlePlayCount, "1× Play", Color(0xFF1FA6A0))
            TapCounter(hubState.doublePlayCount, "2× Play", Color(0xFFB8E4E0))
            TapCounter(hubState.triplePlayCount, "3× Play", Color(0xFFE8F4F3))
        }
        OutlinedTextField(
            value = hubState.multiplicityWindowSecText,
            onValueChange = hub::onMultiplicityWindowTextChange,
            label = { Text("Окно кратности, сек") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                hub.commitMultiplicityWindow()
                focus.clearFocus()
            }),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hubState.multiplicitySettleSecText,
            onValueChange = hub::onMultiplicitySettleTextChange,
            label = { Text("Пауза зачёта, сек") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                hub.commitMultiplicitySettle()
                focus.clearFocus()
            }),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = hubState.playLockoutSecText,
            onValueChange = hub::onPlayLockoutTextChange,
            label = { Text("Блокировка Play, сек") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                hub.commitPlayLockout()
                focus.clearFocus()
            }),
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Text(
            text = "Последнее: ${last}",
            color = Cream,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { log.clear() }) {
                Text("Очистить лог", color = Cream)
            }
            TextButton(onClick = { app.headsetHub.resetCounter() }) {
                Text("Сбросить все счётчики", color = Cream)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TapButton("1×") { app.headsetHub.simulateTapCount(1) }
            TapButton("2×") { app.headsetHub.simulateTapCount(2) }
            TapButton("3×") { app.headsetHub.simulateTapCount(3) }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)) {
            items(log) { line ->
                Text(
                    text = line,
                    color = Cream,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Cream,
    unfocusedTextColor = Cream,
    focusedLabelColor = Muted,
    unfocusedLabelColor = Muted,
    cursorColor = Cream,
    focusedBorderColor = Color(0xFF1FA6A0),
    unfocusedBorderColor = Color(0xFF3A6A72),
)

@Composable
private fun TapCounter(count: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 56.sp,
            style = MaterialTheme.typography.displayLarge,
            color = color,
        )
        Text(
            text = label,
            color = Muted,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun TapButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Cream),
    ) {
        Text(label)
    }
}

private fun formatTap(event: HeadsetButtonEvent): String {
    val name = when (event.tapCount) {
        3 -> "Тройной Play"
        2 -> "Двойной Play"
        else -> "Одиночный Play"
    }
    return "${event.at}  $name  (${event.kind})"
}
