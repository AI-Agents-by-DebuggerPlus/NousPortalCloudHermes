package com.nous.ahcc.presentation.telegram

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.AhccApp
import com.nous.ahcc.data.telegram.TelegramUserAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TelegramLoginScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as AhccApp
    val session = app.telegramUser
    val auth by session.auth.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phoneSent by remember { mutableStateOf(false) }

    LaunchedEffect(auth, phone, phoneSent) {
        if (auth is TelegramUserAuth.NeedPhone && phone.isNotBlank() && !phoneSent) {
            phoneSent = true
            session.submitPhone(phone)
        }
    }

    LaunchedEffect(Unit) {
        val saved = app.preferences.telegramLoginFlow.first()
        apiId = saved.apiId.takeIf { it != 0 }?.toString().orEmpty()
        apiHash = saved.apiHash
        phone = saved.phone
        session.apiId = saved.apiId
        session.apiHash = saved.apiHash
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color(0xFFE8F4F3),
        unfocusedTextColor = Color(0xFFE8F4F3),
        focusedBorderColor = Color(0xFF1FA6A0),
        unfocusedBorderColor = Color(0xFF3A6A72),
        cursorColor = Color(0xFFE8F4F3),
        focusedLabelColor = Color(0xFF8AA8A6),
        unfocusedLabelColor = Color(0xFF8AA8A6),
        focusedContainerColor = Color(0xFF123D45),
        unfocusedContainerColor = Color(0xFF123D45)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1F2A))
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFFE8F4F3))
            }
            Text("Вход в Telegram", style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE8F4F3))
        }
        Text(
            text = "Сеанс уже установленного Telegram приложению недоступен. AHCC входит отдельно, один раз. Код подтверждения приходит в открытый Telegram.",
            color = Color(0xFF8AA8A6),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = statusText(auth),
            color = Color(0xFFE8F4F3),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = apiId,
            onValueChange = { apiId = it.filter { ch -> ch.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("api_id") },
            colors = fieldColors
        )
        OutlinedTextField(
            value = apiHash,
            onValueChange = { apiHash = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("api_hash") },
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Телефон +1…") },
            colors = fieldColors
        )
        val signedIn = auth is TelegramUserAuth.Ready
        Button(
            onClick = {
                val id = apiId.toIntOrNull() ?: 0
                scope.launch {
                    phoneSent = false
                    app.preferences.saveTelegramLogin(id, apiHash, phone)
                    session.begin(id, apiHash)
                }
            },
            enabled = !signedIn && apiId.isNotBlank() && apiHash.isNotBlank() && phone.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (signedIn) "Вход выполнен" else "Отправить код") }

        if (auth is TelegramUserAuth.NeedCode || auth is TelegramUserAuth.Failed) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Код из Telegram") },
                colors = fieldColors
            )
            Button(
                onClick = { session.submitCode(code) },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Подтвердить код") }
        }
        if (auth is TelegramUserAuth.NeedPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Пароль 2FA") },
                visualTransformation = PasswordVisualTransformation(),
                colors = fieldColors
            )
            Button(
                onClick = { session.submitPassword(password) },
                enabled = password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Войти") }
        }
    }
}

private fun statusText(auth: TelegramUserAuth): String = when (auth) {
    TelegramUserAuth.Starting -> "Ожидание сеанса TDLib"
    TelegramUserAuth.NeedPhone -> "Введите телефон и отправьте код"
    TelegramUserAuth.NeedCode -> "Код отправлен в ваш Telegram"
    is TelegramUserAuth.NeedPassword -> "Нужен пароль двухфакторной защиты" +
        auth.hint.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    TelegramUserAuth.Ready -> "Вход выполнен. Голосовые пойдут от вашего аккаунта."
    is TelegramUserAuth.Failed -> auth.message
}
