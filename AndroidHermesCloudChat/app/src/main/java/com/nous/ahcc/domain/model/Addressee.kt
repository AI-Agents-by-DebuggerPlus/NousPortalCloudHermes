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
        const val LIAISON_ID = "liaison"

        val LIAISON = Addressee(LIAISON_ID, "MainAgent")

        val defaultCatalog: List<Addressee> = listOf(
            LIAISON,
            Addressee("english_tutor", "EnglishTutor"),
        )

        /** @deprecated use [defaultCatalog] or UI state list */
        val catalog: List<Addressee> get() = defaultCatalog

        val predefined: List<Addressee> get() = defaultCatalog

        val default: Addressee = LIAISON

        val sessionEndTriggers: Set<String> = setOf(
            "завершить",
            "закончить",
            "стоп",
            "хватит",
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

        fun normalizeCatalog(list: List<Addressee>): List<Addressee> =
            list.map { item ->
                if (item.id == LIAISON_ID && item.displayName.equals("Gate", ignoreCase = true)) {
                    item.copy(displayName = LIAISON.displayName)
                } else {
                    item
                }
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
            if (addressee.id == LIAISON_ID) return body
            return "TO: ${addressee.id}\n$body"
        }
    }
}
