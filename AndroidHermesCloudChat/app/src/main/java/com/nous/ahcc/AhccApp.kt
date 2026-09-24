package com.nous.ahcc

import android.app.Application
import com.nous.ahcc.data.HermesChatGateway
import com.nous.ahcc.data.local.ConnectionPreferences
import com.nous.ahcc.data.telegram.TelegramUserSession
import com.nous.ahcc.headset.HeadsetButtonHub
import com.nous.ahcc.headset.HeadsetButtonPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AhccApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var preferences: ConnectionPreferences
        private set

    lateinit var chatGateway: HermesChatGateway
        private set

    lateinit var headsetButtonPreferences: HeadsetButtonPreferences
        private set

    lateinit var headsetHub: HeadsetButtonHub
        private set

    lateinit var telegramUser: TelegramUserSession
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = ConnectionPreferences(this)
        telegramUser = TelegramUserSession(this)
        chatGateway = HermesChatGateway()
        headsetButtonPreferences = HeadsetButtonPreferences(this)
        headsetHub = HeadsetButtonHub(headsetButtonPreferences)
        appScope.launch {
            val login = preferences.telegramLoginFlow.first()
            telegramUser.restoreSavedSession(login.apiId, login.apiHash)
        }
    }
}
