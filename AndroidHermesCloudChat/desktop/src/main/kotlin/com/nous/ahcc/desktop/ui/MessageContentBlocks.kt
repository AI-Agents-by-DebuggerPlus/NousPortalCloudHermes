package com.nous.ahcc.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

sealed class ContentBlock {
    data class Paragraph(val text: String) : ContentBlock()
    data class Code(val title: String?, val code: String) : ContentBlock()
}

/** Telegram-style fenced blocks: ```lang\n...\n``` or ```\n...\n``` */
private val fenceRegex = Regex("```([^\\n`]*)\\r?\\n([\\s\\S]*?)```")

/**
 * Split message into plain paragraphs and code cards.
 * Only ```…``` becomes a frame — same rule as Telegram pre/preCode.
 */
fun parseContentBlocks(raw: String): List<ContentBlock> {
    if (raw.isBlank()) return emptyList()
    val matches = fenceRegex.findAll(raw).toList()
    if (matches.isEmpty()) {
        return splitParagraphs(raw)
    }
    val blocks = mutableListOf<ContentBlock>()
    var cursor = 0
    for (match in matches) {
        val before = raw.substring(cursor, match.range.first).trim()
        if (before.isNotEmpty()) {
            blocks += splitParagraphs(before)
        }
        val title = match.groupValues[1].trim().ifEmpty { null }
        val code = match.groupValues[2].trimEnd('\n')
        if (code.isNotEmpty() || title != null) {
            blocks += ContentBlock.Code(title, code)
        }
        cursor = match.range.last + 1
    }
    val tail = raw.substring(cursor).trim()
    if (tail.isNotEmpty()) {
        blocks += splitParagraphs(tail)
    }
    return blocks
}

private fun splitParagraphs(text: String): List<ContentBlock> =
    text.split(Regex("\\n{2,}")).mapNotNull { part ->
        part.trim().takeIf { it.isNotEmpty() }?.let { ContentBlock.Paragraph(it) }
    }

@Composable
fun MessageBody(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseContentBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                    color = color,
                )
                is ContentBlock.Code -> TelegramCodeBlock(title = block.title, code = block.code)
            }
        }
    }
}

@Composable
fun TelegramCodeBlock(
    title: String?,
    code: String,
) {
    var copied by remember(code) { mutableStateOf(false) }
    val header = title?.ifBlank { null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TgTheme.CodeBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (header != null) {
                Text(
                    text = header,
                    color = TgTheme.CodeHeader,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        copyToClipboard(code)
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    tint = TgTheme.Secondary,
                    modifier = Modifier.padding(end = 4.dp).size(14.dp),
                )
                Text(
                    if (copied) "copied" else "copy",
                    color = TgTheme.Secondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = code,
                color = TgTheme.Text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
