package com.nous.ahcc.desktop.config

import java.io.File
import java.util.Properties

object DesktopUiSettings {
    private val file: File by lazy {
        File(System.getProperty("user.home"), ".ahcc-desktop/ui.properties").also { it.parentFile?.mkdirs() }
    }

    var startOnLaunch: Boolean
        get() = load().getProperty("startOnLaunch", "true").toBoolean()
        set(value) {
            val props = load()
            props.setProperty("startOnLaunch", value.toString())
            save(props)
        }

    var sessionId: String
        get() = load().getProperty("sessionId", "").orEmpty()
        set(value) {
            val props = load()
            props.setProperty("sessionId", value)
            save(props)
        }

    var supabaseUrl: String
        get() = load().getProperty("supabaseUrl", HermesConfig.SUPABASE_URL).orEmpty()
            .ifBlank { HermesConfig.SUPABASE_URL }
        set(value) {
            val props = load()
            props.setProperty("supabaseUrl", value)
            save(props)
        }

    var supabaseAnonKey: String
        get() = load().getProperty("supabaseAnonKey", HermesConfig.SUPABASE_ANON_KEY).orEmpty()
            .ifBlank { HermesConfig.SUPABASE_ANON_KEY }
        set(value) {
            val props = load()
            props.setProperty("supabaseAnonKey", value)
            save(props)
        }

    fun persistConnection(
        sessionId: String,
        supabaseUrl: String,
        supabaseAnonKey: String,
        startOnLaunch: Boolean,
    ) {
        val props = load()
        props.setProperty("sessionId", sessionId)
        props.setProperty("supabaseUrl", supabaseUrl)
        props.setProperty("supabaseAnonKey", supabaseAnonKey)
        props.setProperty("startOnLaunch", startOnLaunch.toString())
        save(props)
    }

    private fun load(): Properties {
        val props = Properties()
        if (file.isFile) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }

    private fun save(props: Properties) {
        file.outputStream().use { props.store(it, "AHCC Desktop UI settings") }
    }
}
