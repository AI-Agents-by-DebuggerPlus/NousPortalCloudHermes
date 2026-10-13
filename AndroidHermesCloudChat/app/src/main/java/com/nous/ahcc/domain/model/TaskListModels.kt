package com.nous.ahcc.domain.model

import org.json.JSONArray
import org.json.JSONObject

enum class TaskStatus {
    Open,
    Done,
    Cancelled,
    ;

    fun wire(): String = when (this) {
        Open -> "open"
        Done -> "done"
        Cancelled -> "cancelled"
    }

    companion object {
        fun fromWire(raw: String?): TaskStatus = when (raw?.trim()?.lowercase()) {
            "done", "completed", "complete" -> Done
            "cancelled", "canceled", "cancel" -> Cancelled
            else -> Open
        }
    }
}

data class TaskItem(
    val id: String,
    val title: String,
    val status: TaskStatus = TaskStatus.Open,
)

object TaskListParser {
    fun hasSnapshot(text: String): Boolean = parseSnapshot(text) != null

    /** Last `{"skill":"tasks",…}` object in the reply. */
    fun parseSnapshot(text: String): List<TaskItem>? {
        var found: List<TaskItem>? = null
        var i = 0
        while (i < text.length) {
            val start = text.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            parseObject(text.substring(start, end + 1))?.let { found = it }
            i = end + 1
        }
        return found
    }

    private fun parseObject(json: String): List<TaskItem>? {
        return try {
            val root = JSONObject(json)
            if (!root.optString("skill").equals("tasks", ignoreCase = true)) return null
            val arr = when {
                root.has("items") -> root.optJSONArray("items")
                root.has("tasks") -> root.optJSONArray("tasks")
                else -> null
            } ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").trim()
                    val title = o.optString("title").trim()
                    if (id.isEmpty() || title.isEmpty()) continue
                    add(
                        TaskItem(
                            id = id,
                            title = title,
                            status = TaskStatus.fromWire(o.optString("status")),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun encodeSnapshot(items: List<TaskItem>): String {
        val arr = JSONArray()
        items.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("status", t.status.wire()),
            )
        }
        return JSONObject()
            .put("skill", "tasks")
            .put("items", arr)
            .toString()
    }

    fun stripSnapshotLines(text: String): String {
        val lines = text.lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("{") && t.contains("\"skill\"") && t.contains("tasks")
        }
        return lines.joinToString("\n").trim()
    }

    fun displaySummary(items: List<TaskItem>): String {
        val open = items.count { it.status == TaskStatus.Open }
        val done = items.count { it.status == TaskStatus.Done }
        return when {
            items.isEmpty() -> "Список задач пуст."
            else -> "Задачи обновлены: открыто $open, готово $done."
        }
    }

    /**
     * English TTS/chat confirmation after a tasks snapshot.
     * Prefer “Task … added to the list” when a new open item appears.
     */
    fun confirmSpeech(previous: List<TaskItem>, current: List<TaskItem>): String {
        val prevIds = previous.map { it.id }.toSet()
        val added = current.filter { it.status == TaskStatus.Open && it.id !in prevIds }
        if (added.size == 1) {
            return """Task "${added[0].title}" added to the list."""
        }
        if (added.size > 1) {
            val names = added.joinToString("; ") { """"${it.title}"""" }
            return "Tasks added to the list: $names."
        }
        val prevById = previous.associateBy { it.id }
        val newlyDone = current.filter { t ->
            t.status == TaskStatus.Done &&
                prevById[t.id]?.status != TaskStatus.Done
        }
        if (newlyDone.size == 1) {
            return """Task "${newlyDone[0].title}" marked done."""
        }
        val removed = previous.filter { p -> current.none { it.id == p.id } }
        if (removed.size == 1) {
            return """Task "${removed[0].title}" removed from the list."""
        }
        return ""
    }
}
