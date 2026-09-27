package com.nous.ahcc.headset

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Speaks when an audio headset connects or drops. */
class HeadsetLinkAnnouncer(
    private val context: Context,
    private val speak: (String) -> Unit,
) {
    private var lastKey = ""
    private var lastAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val connected = state == BluetoothProfile.STATE_CONNECTED
            val dropped = state == BluetoothProfile.STATE_DISCONNECTED
            if (!connected && !dropped) return
            val device = deviceExtra(intent) ?: return
            if (!isAudioDevice(device)) return
            val name = deviceName(device)
            val phrase = if (connected) "$name connected, AHCC" else "$name disconnected, AHCC"
            val key = "$name:$connected"
            val now = System.currentTimeMillis()
            if (key == lastKey && now - lastAt < 4_000) return
            lastKey = key
            lastAt = now
            speak(phrase)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isAudioDevice(device: BluetoothDevice): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED
    }

    private fun deviceName(device: BluetoothDevice): String {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val name = if (allowed) runCatching { device.name }.getOrNull() else null
        return name?.trim()?.takeIf { it.isNotEmpty() } ?: "Headset"
    }
}
