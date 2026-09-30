package com.nous.ahcc.domain.model

/**
 * Chat addressee (routing target for Hermes).
 *
 * **Where to add or remove addressees:** edit [catalog] in this file.
 * UI in Settings only shows the current list; there is no in-app editor yet.
 * Later: sync with Nous Dashboard / DataStore.
 */
data class Addressee(
    val id: String,
    val displayName: String,
) {
    companion object {
        const val LIAISON_ID = "liaison"

        /** Stable id `liaison` — default route (no `TO:` line on the wire). */
        val LIAISON = Addressee(LIAISON_ID, "Gate")

        /**
         * Authoritative list for the dropdown. Add/remove lines here, then rebuild AHCC.
         * `id` — internal key; `displayName` — label in UI and in `TO: …` for the agent.
         */
        val catalog: List<Addressee> = listOf(
            LIAISON,
            Addressee("english_tutor", "EnglishTutor"),
        )

        /** @deprecated use [catalog] */
        val predefined: List<Addressee> get() = catalog

        val default: Addressee = LIAISON

        val sessionEndTriggers: Set<String> = setOf(
            "завершить",
            "закончить",
            "стоп",
            "хватит",
        )

        fun isSessionEndCommand(text: String): Boolean {
            val normalized = text.trim().lowercase().trim { it in ".,!?;:\"'«»" }
            return normalized in sessionEndTriggers
        }

        fun wireText(userText: String, addressee: Addressee): String {
            val body = userText.trim()
            if (body.isEmpty()) return body
            if (addressee.id == LIAISON_ID) return body
            return "TO: ${addressee.displayName}\n$body"
        }
    }
}
