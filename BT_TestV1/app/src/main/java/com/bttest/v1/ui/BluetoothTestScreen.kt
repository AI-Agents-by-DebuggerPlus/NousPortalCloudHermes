package com.bttest.v1.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bttest.v1.BtTestApp
import com.bttest.v1.headset.HeadsetButtonNames
import com.bttest.v1.headset.HeadsetMonitorService

@Composable
fun BluetoothTestScreen(
    versionLabel: String = "",
    onBack: (() -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as Application
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val vm: BluetoothTestViewModel = viewModel(
        factory = BluetoothTestViewModelFactory(app)
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.setCaptureEnabled(true)
    }

    fun enableCaptureWithPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (need.isEmpty()) {
            vm.setCaptureEnabled(true)
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BT_TestV1",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (versionLabel.isNotBlank()) {
                    Text(
                        text = versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "Одиночный Play → Play. Двойной Play / жест Next → Next. " +
                "Если HARDWARE нет — нажмите Reassert и force-stop AHCC/Spotify/YouTube.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (state.captureOn) {
                    "Native capture: ON (MediaSession)"
                } else {
                    "Native capture: OFF"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.captureOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = state.captureOn,
                onCheckedChange = { enabled ->
                    if (enabled) enableCaptureWithPermissions()
                    else vm.setCaptureEnabled(false)
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Защита от повторного нажатия",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = state.debounceEnabled,
                onCheckedChange = vm::setDebounceEnabled
            )
        }

        OutlinedTextField(
            value = state.debounceIntervalText,
            onValueChange = vm::onDebounceIntervalTextChange,
            enabled = state.debounceEnabled,
            label = { Text("Интервал защиты (мс)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    vm.commitDebounceInterval()
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state.nextDoubleTapText,
            onValueChange = vm::onNextDoubleTapTextChange,
            label = { Text("Интервал Next / двойного Play (мс)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    vm.commitNextDoubleTapInterval()
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.pressCount.toString(),
                    fontSize = 56.sp,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Play",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.nextCount.toString(),
                    fontSize = 56.sp,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Next",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        Text(
            text = "Последнее: ${state.lastLabel.ifBlank { "—" }} · ${state.lastAt.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        TextButton(onClick = vm::reset) {
            Text("Сбросить счётчики")
        }

        OutlinedButton(
            onClick = vm::reassert,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reassert MediaSession")
        }

        SimulateButtons(onSimulate = vm::simulate)

        HorizontalDivider()

        Text(
            text = "Журнал (HARDWARE / SIMULATED)",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth()
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state.eventLog.isEmpty()) {
                item {
                    Text(
                        text = "Пока нет событий",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.eventLog) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulateButtons(onSimulate: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { onSimulate("MEDIA_PLAY") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Симулировать Play")
        }
        Text(
            text = "Два быстрых «Play» → Next (2×Play). Отдельная кнопка Next — аппаратный Next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeadsetButtonNames.simulateLabels
                .filter { it != "MEDIA_PLAY" }
                .forEach { label ->
                    OutlinedButton(onClick = { onSimulate(label) }) {
                        Text(label.removePrefix("MEDIA_"))
                    }
                }
        }
    }
}

class BluetoothTestViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val hub = (application as BtTestApp).headsetHub
    val state = hub.state

    fun setCaptureEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        if (enabled) {
            HeadsetMonitorService.start(ctx)
            HeadsetMonitorService.reassert(ctx)
        } else {
            HeadsetMonitorService.stop(ctx)
        }
    }

    fun reassert() {
        HeadsetMonitorService.reassert(getApplication())
    }

    fun setDebounceEnabled(enabled: Boolean) = hub.setDebounceEnabled(enabled)

    fun onDebounceIntervalTextChange(text: String) = hub.onDebounceIntervalTextChange(text)

    fun commitDebounceInterval() = hub.commitDebounceInterval()

    fun onNextDoubleTapTextChange(text: String) = hub.onNextDoubleTapTextChange(text)

    fun commitNextDoubleTapInterval() = hub.commitNextDoubleTapInterval()

    fun reset() = hub.resetCounter()

    fun simulate(label: String) = hub.simulate(label)
}

class BluetoothTestViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BluetoothTestViewModel::class.java)) {
            return BluetoothTestViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}

