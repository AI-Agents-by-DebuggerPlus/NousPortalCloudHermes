package com.nous.ahcc.desktop.model

enum class ChatChannel {
    /** User TDLib session → Hermes bot (same as Android AHCC). */
    Telegram,

    /** Nous Inference HTTP SSE (no server tools / TO: routing). */
    HttpSse,
    ;

    companion object {
        fun parse(raw: String?): ChatChannel =
            entries.find { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Telegram
    }
}
