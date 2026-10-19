package com.nous.ahcc.desktop.model

/**
 * Chat addressee (routing target for Hermes). Mirrors Android AHCC catalog.
 *
 * [telegramBotUsername] — optional per-addressee bot (@ omitted). When set, Desktop
 * talks to that bot and does not prefix `TO:` (bot-per-profile). When blank, falls
 * back to ConnectionConfig.telegramBotUsername and uses `TO:` for non–MainAgent.
 * [telegramBotToken] — BotFather token for that bot (stored locally; used when binding /
 * documenting CHANNELS; messaging still goes via the signed-in Telegram user session).
 */
data class Addressee(
    val id: String,
    val displayName: String,
    val telegramBotUsername: String = "",
    val telegramBotToken: String = "",
) {
    companion object {
        const val MAIN_AGENT_ID = "main_agent"
        const val OPERATOR_ID = "operator"

        val MAIN_AGENT = Addressee(MAIN_AGENT_ID, "MainAgent")
        val ASSISTANT = Addressee("assistant", "Assistant")
        val ENGLISH_TUTOR = Addressee("english_tutor", "EnglishTutor")
        val TASK_SCHEDULER = Addressee("task_scheduler", "TaskScheduler")
        val BIOSTACK = Addressee("biostack", "BioStack")
        val ACCOUNTANT = Addressee("accountant", "Accountant")
        val SYSADMIN = Addressee("sysadmin", "SysAdmin")
        val TRADER = Addressee("trader", "Trader")
        val FUTURES_TRADER = Addressee("futures_trader", "FuturesTrader")
        val OPERATOR = Addressee(OPERATOR_ID, "Operator", telegramBotUsername = "npch_operator_bot")

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
            list.joinToString("\n") { a ->
                val name = a.displayName.replace('\t', ' ').replace('\n', ' ')
                val bot = a.telegramBotUsername.trim().removePrefix("@")
                val token = a.telegramBotToken.trim()
                when {
                    token.isNotEmpty() -> "${a.id}\t$name\t$bot\t$token"
                    bot.isNotEmpty() -> "${a.id}\t$name\t$bot"
                    else -> "${a.id}\t$name"
                }
            }

        fun decodeCatalog(raw: String?): List<Addressee> {
            if (raw.isNullOrBlank()) return normalizeCatalog(emptyList())
            return normalizeCatalog(
                raw.lineSequence()
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@mapNotNull null
                        val parts = trimmed.split('\t')
                        if (parts.size < 2) return@mapNotNull null
                        val id = parts[0].trim()
                        val name = parts[1].trim()
                        val bot = parts.getOrNull(2)?.trim()?.removePrefix("@").orEmpty()
                        val token = parts.getOrNull(3)?.trim().orEmpty()
                        if (id.isEmpty() || name.isEmpty()) null
                        else Addressee(
                            id = id,
                            displayName = name,
                            telegramBotUsername = bot,
                            telegramBotToken = token,
                        )
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
                val idx = deduped.indexOfFirst { it.id.equals(builtin.id, ignoreCase = true) }
                if (idx < 0) {
                    deduped += builtin
                } else {
                    val existing = deduped[idx]
                    var merged = existing
                    if (existing.telegramBotUsername.isBlank() && builtin.telegramBotUsername.isNotBlank()) {
                        merged = merged.copy(telegramBotUsername = builtin.telegramBotUsername)
                    }
                    if (existing.telegramBotToken.isBlank() && builtin.telegramBotToken.isNotBlank()) {
                        merged = merged.copy(telegramBotToken = builtin.telegramBotToken)
                    }
                    deduped[idx] = merged
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

        /**
         * Effective bot for an addressee: own bot, else [fallbackBotUsername].
         */
        fun effectiveBotUsername(addressee: Addressee, fallbackBotUsername: String): String =
            addressee.telegramBotUsername.trim().removePrefix("@")
                .ifBlank { fallbackBotUsername.trim().removePrefix("@") }

        /**
         * When the addressee has a dedicated bot, send plain body (no TO:).
         * Otherwise MainAgent = plain; others = `TO: id` for shared-bot relay.
         */
        fun wireText(userText: String, addressee: Addressee): String {
            val body = userText.trim()
            if (body.isEmpty()) return body
            if (addressee.telegramBotUsername.isNotBlank()) return body
            if (addressee.id == MAIN_AGENT_ID) return body
            return "TO: ${addressee.id}\n$body"
        }
    }
}
