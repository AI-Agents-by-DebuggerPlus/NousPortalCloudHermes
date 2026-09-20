package com.bttest.v1

import android.app.Application
import com.bttest.v1.headset.HeadsetButtonHub
import com.bttest.v1.headset.HeadsetButtonPreferences

class BtTestApp : Application() {
    lateinit var headsetButtonPreferences: HeadsetButtonPreferences
        private set

    lateinit var headsetHub: HeadsetButtonHub
        private set

    override fun onCreate() {
        super.onCreate()
        headsetButtonPreferences = HeadsetButtonPreferences(this)
        headsetHub = HeadsetButtonHub(headsetButtonPreferences)
    }
}
