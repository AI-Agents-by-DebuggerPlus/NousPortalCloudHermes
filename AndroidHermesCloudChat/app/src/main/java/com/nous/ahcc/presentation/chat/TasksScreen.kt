package com.nous.ahcc.presentation.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nous.ahcc.domain.model.TaskItem
import com.nous.ahcc.domain.model.TaskStatus

private val Cream = Color(0xFFE8F4F3)
private val Muted = Color(0xFF8AA8A6)
private val Panel = Color(0xFF123D45)
private val RowBg = Color(0xFF1A4550)
private val Bg = Color(0xFF0B1F2A)

@Composable
fun TasksScreen(viewModel: HermesChatViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    BackHandler { viewModel.closeTasksScreen() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::closeTasksScreen) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Cream,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Задачи",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Cream,
                )
                Text(
                    text = "TaskScheduler · без сроков",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
            }
            TextButton(onClick = viewModel::refreshTasksFromAgent) {
                Text("Обновить", color = Cream)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Новая задача…", color = Muted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val t = draft.trim()
                        if (t.isNotEmpty()) {
                            viewModel.addTaskFromUi(t)
                            draft = ""
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Cream,
                    unfocusedTextColor = Cream,
                    focusedBorderColor = Cream.copy(alpha = 0.5f),
                    unfocusedBorderColor = Muted.copy(alpha = 0.4f),
                    cursorColor = Cream,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            TextButton(
                onClick = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) {
                        viewModel.addTaskFromUi(t)
                        draft = ""
                    }
                }
            ) {
                Text("Добавить", color = Cream)
            }
        }

        Spacer(Modifier.height(12.dp))

        val open = state.tasks.filter { it.status == TaskStatus.Open }
        val done = state.tasks.filter { it.status == TaskStatus.Done }

        if (state.tasks.isEmpty()) {
            Text(
                text = "Список пуст. Добавьте задачу или выберите TaskScheduler в чате и напишите «список».",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (open.isNotEmpty()) {
                    item {
                        Text("Открытые", color = Muted, style = MaterialTheme.typography.labelLarge)
                    }
                    items(open, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onDone = { viewModel.completeTaskFromUi(task) },
                            onDelete = { viewModel.deleteTaskFromUi(task) },
                        )
                    }
                }
                if (done.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Готово", color = Muted, style = MaterialTheme.typography.labelLarge)
                    }
                    items(done, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onDone = null,
                            onDelete = { viewModel.deleteTaskFromUi(task) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskItem,
    onDone: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val done = task.status == TaskStatus.Done
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RowBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = if (done) Muted else Cream,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.id,
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (onDone != null) {
            TextButton(onClick = onDone) {
                Text("Готово", color = Cream)
            }
        }
        TextButton(onClick = onDelete) {
            Text("Удалить", color = Muted)
        }
    }
}
