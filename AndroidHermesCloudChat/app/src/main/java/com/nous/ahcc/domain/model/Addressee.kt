package com.nous.ahcc.domain.model

/**
 * Chat addressee (routing target for Hermes).
 *
 * Built-in defaults: [defaultCatalog]. Users can add/remove receivers in chat;
 * list is persisted in DataStore. Code defaults apply when storage is empty.
 */
data class Addressee(
    val id: String,
    val displayName: String,
) {
    companion object {
        /** Hermes profile id / TO: key for the front-desk agent. */
        const val MAIN_AGENT_ID = "main_agent"

        /** @deprecated Renamed to [MAIN_AGENT_ID]; kept for migrations. */
        const val LIAISON_ID = MAIN_AGENT_ID

        val MAIN_AGENT = Addressee(MAIN_AGENT_ID, "MainAgent")

        const val ENGLISH_TUTOR_ID = "english_tutor"
        const val TASK_SCHEDULER_ID = "task_scheduler"
        const val ASSISTANT_ID = "assistant"
        const val BIOSTACK_ID = "biostack"
        const val ACCOUNTANT_ID = "accountant"
        const val SYSADMIN_ID = "sysadmin"
        const val TRADER_ID = "trader"
        const val FUTURES_TRADER_ID = "futures_trader"
        const val OPERATOR_ID = "operator"

        val ENGLISH_TUTOR = Addressee(ENGLISH_TUTOR_ID, "EnglishTutor")
        val TASK_SCHEDULER = Addressee(TASK_SCHEDULER_ID, "TaskScheduler")
        val ASSISTANT = Addressee(ASSISTANT_ID, "Assistant")
        val BIOSTACK = Addressee(BIOSTACK_ID, "BioStack")
        val ACCOUNTANT = Addressee(ACCOUNTANT_ID, "Accountant")
        val SYSADMIN = Addressee(SYSADMIN_ID, "SysAdmin")
        val TRADER = Addressee(TRADER_ID, "Trader")
        val FUTURES_TRADER = Addressee(FUTURES_TRADER_ID, "FuturesTrader")
        val OPERATOR = Addressee(OPERATOR_ID, "Operator")

        /** @deprecated use [MAIN_AGENT] */
        val LIAISON: Addressee get() = MAIN_AGENT

        val defaultCatalog: List<Addressee> = listOf(
            MAIN_AGENT,
            ASSISTANT,
            ENGLISH_TUTOR,
            TASK_SCHEDULER,
            BIOSTACK,
            ACCOUNTANT,
            SYSADMIN,
            TRADER,
            FUTURES_TRADER,
            OPERATOR,
        )

        /** @deprecated use [defaultCatalog] or UI state list */
        val catalog: List<Addressee> get() = defaultCatalog

        val predefined: List<Addressee> get() = defaultCatalog

        val default: Addressee = MAIN_AGENT

        fun resolve(catalog: List<Addressee>, id: String): Addressee {
            val normalizedId = if (id == "liaison") MAIN_AGENT_ID else id
            return catalog.find { it.id == normalizedId }
                ?: catalog.find { it.id == MAIN_AGENT_ID }
                ?: catalog.firstOrNull()
                ?: MAIN_AGENT
        }

        val sessionEndTriggers: Set<String> = setOf(
            "завершить",
            "закончить",
            "стоп",
            "хватит",
            "stop_flashcards",
        )

        fun encodeCatalog(list: List<Addressee>): String =
            list.joinToString("\n") { "${it.id}\t${it.displayName.replace('\t', ' ')}" }

        fun decodeCatalog(raw: String?): List<Addressee> {
            if (raw.isNullOrBlank()) return emptyList()
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
            // Built-ins missing from an older saved catalog (e.g. task_scheduler).
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

        /** Non–MainAgent messages prefix with addressing key for Hermes harnesses. */
        fun wireText(userText: String, addressee: Addressee): String {
            val body = userText.trim()
            if (body.isEmpty()) return body
            if (addressee.id == MAIN_AGENT_ID) return body
            return "TO: ${addressee.id}\n$body"
        }
    }
}
