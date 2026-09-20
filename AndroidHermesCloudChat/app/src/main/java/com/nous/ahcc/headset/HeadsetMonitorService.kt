package com.nous.ahcc.headset

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nous.ahcc.AhccApp
import com.nous.ahcc.MainActivity
import com.nous.ahcc.R

/**
 * Foreground service + MediaSession для приёма media-кнопок Bluetooth-гарнитуры.
 * Схема как в AndroidChat: активная MediaSession + STATE_PAUSED + FGS mediaPlayback.
 */
class HeadsetMonitorService : Service() {
    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        attachMediaSession()
        (application as AhccApp).headsetHub.setCaptureOn(true)
        Log.i(TAG, "created, session active=${mediaSession?.isActive}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaSession?.isActive != true) {
            attachMediaSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as AhccApp).headsetHub.setCaptureOn(false)
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
        Log.i(TAG, "destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachMediaSession() {
        if (mediaSession != null) return

        val hub = (application as AhccApp).headsetHub
        val session = MediaSessionCompat(this, "AhccHeadset").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.i(TAG, "onPlay")
                        hub.notifyButton("MEDIA_PLAY")
                    }

                    override fun onPause() {
                        Log.i(TAG, "onPause")
                        hub.notifyButton("MEDIA_PAUSE")
                    }

                    override fun onSkipToNext() {
                        Log.i(TAG, "onSkipToNext")
                        hub.notifyButton("MEDIA_NEXT")
                    }

                    override fun onSkipToPrevious() {
                        Log.i(TAG, "onSkipToPrevious")
                        hub.notifyButton("MEDIA_PREVIOUS")
                    }

                    override fun onStop() {
                        Log.i(TAG, "onStop")
                        hub.notifyButton("MEDIA_STOP")
                    }

                    override fun onMediaButtonEvent(mediaButtonIntent: Intent?): Boolean {
                        val event = extractKeyEvent(mediaButtonIntent)
                            ?: return super.onMediaButtonEvent(mediaButtonIntent)
                        val label = HeadsetButtonNames.fromKeyCode(event.keyCode)
                        Log.i(
                            TAG,
                            "onMediaButtonEvent key=${event.keyCode} action=${event.action} label=$label"
                        )
                        if (label != null &&
                            event.action == KeyEvent.ACTION_DOWN &&
                            event.repeatCount == 0
                        ) {
                            hub.notifyButton(label)
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
                    .build()
            )
            isActive = true
        }
        mediaSession = session
    }

    private fun extractKeyEvent(intent: Intent?): KeyEvent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bt_monitor_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.bt_monitor_channel_desc)
            }
        )
    }

    private fun startAsForeground() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.bt_monitor_title))
            .setContentText(getString(R.string.bt_monitor_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    companion object {
        private const val TAG = "AHCC-BT-SVC"
        private const val CHANNEL_ID = "ahcc_headset"
        private const val NOTIFICATION_ID = 2202

        fun start(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadsetMonitorService::class.java))
        }
    }
}
