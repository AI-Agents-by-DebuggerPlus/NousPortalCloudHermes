package com.nous.ahcc.desktop.model

/**
 * Chat addressee (routing target for Hermes). Mirrors Android AHCC catalog.
 */
data class Addressee(
    val id: String,
    val displayName: String,
) {
    companion object {
        const val MAIN_AGENT_ID = "main_agent"

        val MAIN_AGENT = Addressee(MAIN_AGENT_ID, "MainAgent")
        val ASSISTANT = Addressee("assistant", "Assistant")
        val ENGLISH_TUTOR = Addressee("english_tutor", "EnglishTutor")
        val TASK_SCHEDULER = Addressee("task_scheduler", "TaskScheduler")
        val BIOSTACK = Addressee("biostack", "BioStack")
        val ACCOUNTANT = Addressee("accountant", "Accountant")
        val SYSADMIN = Addressee("sysadmin", "SysAdmin")

        val defaultCatalog: List<Addressee> = listOf(
            MAIN_AGENT,
            ASSISTANT,
            ENGLISH_TUTOR,
            TASK_SCHEDULER,
            BIOSTACK,
            ACCOUNTANT,
            SYSADMIN,
        )

        val default: Addressee = MAIN_AGENT

        private val sessionEndTriggers: Set<String> = setOf(
            "завершить",
            "закончить",
            "стоп",
            "хватит",
            "stop_flashcards",
        )

        fun resolve(catalog: List<Addressee>, id: String): Addressee {
            val normalizedId = if (id == "liaison") MAIN_AGENT_ID else id
            return catalog.find { it.id == normalizedId }
                ?: catalog.find { it.id == MAIN_AGENT_ID }
                ?: catalog.firstOrNull()
                ?: MAIN_AGENT
        }

        fun encodeCatalog(list: List<Addressee>): String =
            list.joinToString("\n") { "${it.id}\t${it.displayName.replace('\t', ' ')}" }

        fun decodeCatalog(raw: String?): List<Addressee> {
            if (raw.isNullOrBlank()) return normalizeCatalog(emptyList())
            return normalizeCatalog(
                raw.lineSequence()
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@mapNotNull null
                        val tab = trimmed.indexOf('\t')
                        if (tab <= 0) return@mapNotNull null
                        val id = trimmed.substring(0, tab).trim()
                        val name = trimmed.substring(tab + 1).trim()
                        if (id.isEmpty() || name.isEmpty()) null else Addressee(id, name)
                    }
                    .distinctBy { it.id }
                    .toList(),
            )
        }

        fun normalizeCatalog(list: List<Addressee>): List<Addressee> {
            val migrated = list.map { item ->
                when {
                    item.id == "liaison" -> item.copy(id = MAIN_AGENT_ID)
                    item.id == MAIN_AGENT_ID && item.displayName.equals("Gate", ignoreCase = true) ->
                        item.copy(displayName = MAIN_AGENT.displayName)
                    else -> item
                }
            }
            val deduped = migrated
                .groupBy { it.id }
                .map { (_, group) -> group.first() }
                .toMutableList()
            for (builtin in defaultCatalog) {
                if (deduped.none { it.id.equals(builtin.id, ignoreCase = true) }) {
                    deduped += builtin
                }
            }
            val hasMain = deduped.any { it.id == MAIN_AGENT_ID }
            return if (hasMain) deduped else listOf(MAIN_AGENT) + deduped.filter { it.id != MAIN_AGENT_ID }
        }

        fun newId(displayName: String, existing: Collection<Addressee>): String {
            val base = displayName.trim().lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "receiver" }
            var candidate = base
            var n = 2
            val taken = existing.map { it.id.lowercase() }.toSet()
            while (candidate.lowercase() in taken) {
                candidate = "${base}_$n"
                n++
            }
            return candidate
        }

        fun isSessionEndCommand(text: String): Boolean {
            val normalized = text.trim().lowercase().trim { it in ".,!?;:\"'«»" }
            return normalized in sessionEndTriggers
        }

        fun wireText(userText: String, addressee: Addressee): String {
            val body = userText.trim()
            if (body.isEmpty()) return body
            if (addressee.id == MAIN_AGENT_ID) return body
            return "TO: ${addressee.id}\n$body"
        }
    }
}
