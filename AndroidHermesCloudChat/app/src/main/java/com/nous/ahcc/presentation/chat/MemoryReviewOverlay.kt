package com.nous.ahcc.presentation.chat



import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.navigationBars

import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.statusBarsPadding

import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.OutlinedButton

import androidx.compose.material3.Surface

import androidx.compose.material3.Text

import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp

import androidx.compose.ui.window.Dialog

import androidx.compose.ui.window.DialogProperties

import com.nous.ahcc.domain.model.MemoryPendingItem
import com.nous.ahcc.domain.model.MemoryReviewSummarizer

import kotlin.math.max



data class MemoryReviewOverlayState(

    val body: String,

    val items: List<MemoryPendingItem> = emptyList(),

    val currentIndex: Int = 0,

) {

    val currentItem: MemoryPendingItem? get() = items.getOrNull(currentIndex)

    val progressLabel: String?

        get() = when {

            items.size <= 1 -> null

            else -> "${currentIndex + 1} / ${items.size}"

        }

}



@Composable

fun MemoryReviewOverlay(

    state: MemoryReviewOverlayState,

    commandsEnabled: Boolean,

    globalAutoApproveActive: Boolean,

    onDismiss: () -> Unit,

    onApplyCurrent: () -> Unit,

    onDenyCurrent: () -> Unit,

    onApplyAllSequential: () -> Unit,

    onDenyAllSequential: () -> Unit,

    onGlobalApplyAll: () -> Unit,

) {

    val bodyScroll = rememberScrollState()

    val density = LocalDensity.current

    val navBottomPx = WindowInsets.navigationBars.getBottom(density)

    val actionBottomPad = with(density) { max(navBottomPx, 56.dp.roundToPx()).toDp() + 12.dp }



    Dialog(

        onDismissRequest = onDismiss,

        properties = DialogProperties(

            dismissOnBackPress = true,

            dismissOnClickOutside = false,

            decorFitsSystemWindows = false,

            usePlatformDefaultWidth = false,

        ),

    ) {

        Surface(

            modifier = Modifier

                .fillMaxSize()

                .background(Color(0xE6000000)),

            color = Color.Transparent,

        ) {

            Column(

                modifier = Modifier

                    .fillMaxSize()

                    .statusBarsPadding()

                    .padding(horizontal = 16.dp, vertical = 12.dp),

            ) {

                Column(

                    modifier = Modifier

                        .weight(1f)

                        .fillMaxWidth()

                        .background(Color(0xFF123D45), RoundedCornerShape(12.dp))

                        .padding(horizontal = 14.dp, vertical = 12.dp),

                ) {

                    Text(

                        text = "Память Hermes",

                        style = MaterialTheme.typography.titleMedium,

                        fontWeight = FontWeight.SemiBold,

                        color = Color(0xFFE8F4F3),

                    )

                    Text(

                        text = "Фоновый review — нужно подтверждение или отклонение.",

                        style = MaterialTheme.typography.bodySmall,

                        color = Color(0xFF8AA8A6),

                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),

                    )

                    state.progressLabel?.let { progress ->

                        Text(

                            text = "Изменение $progress",

                            style = MaterialTheme.typography.labelLarge,

                            color = Color(0xFF1FA6A0),

                            modifier = Modifier.padding(bottom = 6.dp),

                        )

                    }

                    if (globalAutoApproveActive) {

                        Text(

                            text = "Global Apply all: следующие уведомления будут одобряться автоматически (ответы останутся в чате).",

                            style = MaterialTheme.typography.bodySmall,

                            color = Color(0xFF1FA6A0),

                            modifier = Modifier.padding(bottom = 8.dp),

                        )

                    }

                    Column(

                        modifier = Modifier

                            .weight(1f)

                            .fillMaxWidth()

                            .verticalScroll(bodyScroll),

                    ) {

                        val current = state.currentItem

                        if (current != null) {

                            Text(

                                text = MemoryReviewSummarizer.summarizeItem(current),

                                style = MaterialTheme.typography.bodyLarge,

                                fontWeight = FontWeight.Medium,

                                color = Color(0xFFE8F4F3),

                            )

                            Text(

                                text = "Подробно (как от Hermes):",

                                style = MaterialTheme.typography.labelSmall,

                                color = Color(0xFF8AA8A6),

                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),

                            )

                            Text(

                                text = current.detail,

                                style = MaterialTheme.typography.bodySmall,

                                color = Color(0xFF8AA8A6),

                            )

                        } else {

                            Text(

                                text = MemoryReviewSummarizer.summarizeBody(state.body),

                                style = MaterialTheme.typography.bodyMedium,

                                color = Color(0xFFE8F4F3),

                            )

                        }

                    }

                }

                Column(

                    modifier = Modifier

                        .fillMaxWidth()

                        .windowInsetsPadding(WindowInsets.navigationBars)

                        .padding(top = 12.dp, bottom = actionBottomPad),

                    verticalArrangement = Arrangement.spacedBy(8.dp),

                ) {

                    if (!commandsEnabled) {

                        Text(

                            text = "Кнопки недоступны, пока идёт другой запрос к Hermes…",

                            color = Color(0xFF8AA8A6),

                            style = MaterialTheme.typography.bodySmall,

                        )

                    }

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                    ) {

                        Button(

                            onClick = onApplyCurrent,

                            enabled = commandsEnabled,

                            modifier = Modifier.weight(1f),

                            colors = ButtonDefaults.buttonColors(

                                containerColor = Color(0xFF1A4550),

                                contentColor = Color(0xFFE8F4F3),

                                disabledContainerColor = Color(0xFF1A4550).copy(alpha = 0.4f),

                                disabledContentColor = Color(0xFF8AA8A6),

                            ),

                        ) {

                            Text("Apply")

                        }

                        OutlinedButton(

                            onClick = onDenyCurrent,

                            enabled = commandsEnabled,

                            modifier = Modifier.weight(1f),

                            colors = ButtonDefaults.outlinedButtonColors(

                                contentColor = Color(0xFFE8F4F3),

                                disabledContentColor = Color(0xFF8AA8A6),

                            ),

                        ) {

                            Text("Deny")

                        }

                    }

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                    ) {

                        Button(

                            onClick = onApplyAllSequential,

                            enabled = commandsEnabled,

                            modifier = Modifier.weight(1f),

                            colors = ButtonDefaults.buttonColors(

                                containerColor = Color(0xFF1FA6A0),

                                contentColor = Color(0xFF0B1F2A),

                                disabledContainerColor = Color(0xFF1FA6A0).copy(alpha = 0.35f),

                                disabledContentColor = Color(0xFF8AA8A6),

                            ),

                        ) {

                            Text("Apply all")

                        }

                        OutlinedButton(

                            onClick = onDenyAllSequential,

                            enabled = commandsEnabled,

                            modifier = Modifier.weight(1f),

                            colors = ButtonDefaults.outlinedButtonColors(

                                contentColor = Color(0xFFE8F4F3),

                                disabledContentColor = Color(0xFF8AA8A6),

                            ),

                        ) {

                            Text("Deny all")

                        }

                    }

                    Row(

                        modifier = Modifier.fillMaxWidth(),

                        horizontalArrangement = Arrangement.spacedBy(8.dp),

                    ) {

                        Button(

                            onClick = onGlobalApplyAll,

                            enabled = commandsEnabled,

                            modifier = Modifier.weight(1f),

                            colors = ButtonDefaults.buttonColors(

                                containerColor = Color(0xFF2A6A62),

                                contentColor = Color(0xFFE8F4F3),

                                disabledContainerColor = Color(0xFF2A6A62).copy(alpha = 0.35f),

                                disabledContentColor = Color(0xFF8AA8A6),

                            ),

                        ) {

                            Text("Global Apply all")

                        }

                        TextButton(

                            onClick = onDismiss,

                            modifier = Modifier.weight(1f),

                        ) {

                            Text("Закрыть", color = Color(0xFF8AA8A6))

                        }

                    }

                }

            }

        }

    }

}


