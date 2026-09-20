package com.nous.ahcc

import android.app.Application
import com.nous.ahcc.data.HermesChatGateway
import com.nous.ahcc.data.local.ConnectionPreferences
import com.nous.ahcc.headset.HeadsetButtonHub
import com.nous.ahcc.headset.HeadsetButtonPreferences

class AhccApp : Application() {
    lateinit var preferences: ConnectionPreferences
        private set

    lateinit var chatGateway: HermesChatGateway
        private set

    lateinit var headsetButtonPreferences: HeadsetButtonPreferences
        private set

    lateinit var headsetHub: HeadsetButtonHub
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = ConnectionPreferences(this)
        chatGateway = HermesChatGateway()
        headsetButtonPreferences = HeadsetButtonPreferences(this)
        headsetHub = HeadsetButtonHub(headsetButtonPreferences)
    }
}
