package com.nous.ahcc.desktop.config

import com.nous.ahcc.desktop.model.Addressee
import com.nous.ahcc.desktop.model.ChatChannel
import com.nous.ahcc.desktop.model.ConnectionConfig
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

    /** Folder where inbound Telegram files (from agents) are saved automatically. */
    var inboundFilesDir: String
        get() = load().getProperty("inboundFilesDir", defaultInboundFilesDir()).orEmpty()
            .ifBlank { defaultInboundFilesDir() }
        set(value) {
            val props = load()
            props.setProperty("inboundFilesDir", value.trim())
            save(props)
        }

    fun defaultInboundFilesDir(): String =
        File(System.getProperty("user.home"), "AHCC-Downloads").absolutePath

    var sessionId: String
        get() = load().getProperty("sessionId", "").orEmpty()
        set(value) {
            val props = load()
            props.setProperty("sessionId", value)
            save(props)
        }

    var channel: ChatChannel
        get() = ChatChannel.parse(load().getProperty("channel", ChatChannel.Telegram.name))
        set(value) {
            val props = load()
            props.setProperty("channel", value.name)
            save(props)
        }

    fun loadConnection(): ConnectionConfig {
        val p = load()
        return ConnectionConfig(
            channel = ChatChannel.parse(p.getProperty("channel", ChatChannel.Telegram.name)),
            apiKey = p.getProperty("apiKey", HermesConfig.API_KEY).orEmpty()
                .ifBlank { HermesConfig.API_KEY },
            sessionId = p.getProperty("sessionId", HermesConfig.SESSION_ID).orEmpty()
                .ifBlank { HermesConfig.SESSION_ID },
            inferenceBaseUrl = p.getProperty("inferenceBaseUrl", HermesConfig.INFERENCE_BASE_URL)
                .orEmpty().ifBlank { HermesConfig.INFERENCE_BASE_URL },
            model = p.getProperty("model", HermesConfig.MODEL).orEmpty().ifBlank { HermesConfig.MODEL },
            host = p.getProperty("host", HermesConfig.HOST).orEmpty().ifBlank { HermesConfig.HOST },
            port = p.getProperty("port", HermesConfig.PORT.toString())?.toIntOrNull() ?: HermesConfig.PORT,
            useTls = p.getProperty("useTls", HermesConfig.USE_TLS.toString()).toBoolean(),
            agentApiBaseUrl = p.getProperty("agentApiBaseUrl", HermesConfig.AGENT_API_BASE_URL).orEmpty(),
            supabaseUrl = p.getProperty("supabaseUrl", HermesConfig.SUPABASE_URL).orEmpty()
                .ifBlank { HermesConfig.SUPABASE_URL },
            supabaseAnonKey = p.getProperty("supabaseAnonKey", HermesConfig.SUPABASE_ANON_KEY).orEmpty()
                .ifBlank { HermesConfig.SUPABASE_ANON_KEY },
            telegramBotToken = p.getProperty("telegramBotToken", HermesConfig.TELEGRAM_BOT_TOKEN).orEmpty()
                .ifBlank { HermesConfig.TELEGRAM_BOT_TOKEN },
            telegramChatId = p.getProperty("telegramChatId", HermesConfig.TELEGRAM_CHAT_ID).orEmpty()
                .ifBlank { HermesConfig.TELEGRAM_CHAT_ID },
            telegramBotUsername = p.getProperty("telegramBotUsername", HermesConfig.TELEGRAM_BOT_USERNAME)
                .orEmpty().ifBlank { HermesConfig.TELEGRAM_BOT_USERNAME },
            telegramApiId = p.getProperty("telegramApiId", HermesConfig.TELEGRAM_API_ID.toString())
                ?.toIntOrNull() ?: HermesConfig.TELEGRAM_API_ID,
            telegramApiHash = p.getProperty("telegramApiHash", HermesConfig.TELEGRAM_API_HASH).orEmpty()
                .ifBlank { HermesConfig.TELEGRAM_API_HASH },
            telegramPhone = p.getProperty("telegramPhone", HermesConfig.TELEGRAM_PHONE).orEmpty()
                .ifBlank { HermesConfig.TELEGRAM_PHONE },
        )
    }

    var addresseeCatalog: List<Addressee>
        get() = Addressee.decodeCatalog(load().getProperty("addresseeCatalog", ""))
        set(value) {
            val props = load()
            props.setProperty("addresseeCatalog", Addressee.encodeCatalog(value))
            save(props)
        }

    /**
     * True when the properties file really carries an addressee catalog.
     * Guards against overwriting a good file after a blank/partial read.
     */
    val hasAddresseeCatalog: Boolean
        get() = !load().getProperty("addresseeCatalog").isNullOrBlank()

    var defaultAddresseeId: String
        get() = load().getProperty("defaultAddresseeId", Addressee.MAIN_AGENT_ID).orEmpty()
            .ifBlank { Addressee.MAIN_AGENT_ID }
        set(value) {
            val props = load()
            props.setProperty("defaultAddresseeId", value)
            save(props)
        }

    var currentAddresseeId: String
        get() = load().getProperty("currentAddresseeId", defaultAddresseeId).orEmpty()
            .ifBlank { defaultAddresseeId }
        set(value) {
            val props = load()
            props.setProperty("currentAddresseeId", value)
            save(props)
        }

    fun persistAddressees(
        catalog: List<Addressee>,
        defaultId: String,
        currentId: String,
    ) {
        val props = load()
        props.setProperty("addresseeCatalog", Addressee.encodeCatalog(catalog))
        props.setProperty("defaultAddresseeId", defaultId)
        props.setProperty("currentAddresseeId", currentId)
        save(props)
    }

    fun persistConnection(c: ConnectionConfig, startOnLaunch: Boolean) {
        val props = load()
        props.setProperty("channel", c.channel.name)
        props.setProperty("sessionId", c.sessionId)
        props.setProperty("supabaseUrl", c.supabaseUrl)
        props.setProperty("supabaseAnonKey", c.supabaseAnonKey)
        props.setProperty("apiKey", c.apiKey)
        props.setProperty("inferenceBaseUrl", c.inferenceBaseUrl)
        props.setProperty("model", c.model)
        props.setProperty("host", c.host)
        props.setProperty("port", c.port.toString())
        props.setProperty("useTls", c.useTls.toString())
        props.setProperty("agentApiBaseUrl", c.agentApiBaseUrl)
        props.setProperty("telegramBotToken", c.telegramBotToken)
        props.setProperty("telegramChatId", c.telegramChatId)
        props.setProperty("telegramBotUsername", c.telegramBotUsername)
        props.setProperty("telegramApiId", c.telegramApiId.toString())
        props.setProperty("telegramApiHash", c.telegramApiHash)
        props.setProperty("telegramPhone", c.telegramPhone)
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
