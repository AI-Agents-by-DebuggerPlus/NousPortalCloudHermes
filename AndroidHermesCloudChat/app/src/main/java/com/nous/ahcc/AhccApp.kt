package com.nous.ahcc

import android.app.Application
import com.nous.ahcc.data.HermesChatGateway
import com.nous.ahcc.data.local.ConnectionPreferences

class AhccApp : Application() {
    lateinit var preferences: ConnectionPreferences
        private set

    lateinit var chatGateway: HermesChatGateway
        private set

    lateinit var headsetHub: com.nous.ahcc.headset.HeadsetButtonHub
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = ConnectionPreferences(this)
        chatGateway = HermesChatGateway()
        headsetHub = com.nous.ahcc.headset.HeadsetButtonHub()
    }
}
