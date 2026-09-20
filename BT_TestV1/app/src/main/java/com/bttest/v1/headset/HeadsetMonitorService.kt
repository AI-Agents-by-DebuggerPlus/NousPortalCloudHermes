package com.bttest.v1.headset

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bttest.v1.BtTestApp
import com.bttest.v1.MainActivity
import com.bttest.v1.R
import java.util.concurrent.Executors

/**
 * FGS + MediaSession для BT media-кнопок.
 * AudioFocus + PLAYING→PAUSED claim + USAGE_MEDIA pulse (как AndroidEnglishTutor),
 * чтобы перебить AHCC / YouTube / Spotify.
 */
class HeadsetMonitorService : Service() {
    private var mediaSession: MediaSessionCompat? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pulseExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        attachMediaSession(forceReattach = true)
        (application as BtTestApp).headsetHub.setCaptureOn(true)
        Log.i(TAG, "created, session active=${mediaSession?.isActive}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val force = intent?.getBooleanExtra(EXTRA_FORCE_REASSERT, false) == true
        if (force) {
            attachMediaSession(forceReattach = true)
        } else if (mediaSession?.isActive != true) {
            attachMediaSession(forceReattach = mediaSession != null)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as BtTestApp).headsetHub.setCaptureOn(false)
        abandonAudioFocus()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
        Log.i(TAG, "destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attachMediaSession(forceReattach: Boolean = false) {
        if (mediaSession != null && !forceReattach) {
            mediaSession?.isActive = true
            return
        }
        if (forceReattach) {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }

        requestAudioFocus()

        val hub = (application as BtTestApp).headsetHub
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        val session = MediaSessionCompat(this, "BtTestV1Headset").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        Log.i(TAG, "onPlay")
                        hub.notifyButton("MEDIA_PLAY", source = "hardware-callback-onPlay")
                    }

                    override fun onPause() {
                        Log.i(TAG, "onPause")
                        hub.notifyButton("MEDIA_PAUSE", source = "hardware-callback-onPause")
                    }

                    override fun onSkipToNext() {
                        Log.i(TAG, "onSkipToNext")
                        hub.notifyButton("MEDIA_NEXT", source = "hardware-callback-onNext")
                    }

                    override fun onSkipToPrevious() {
                        Log.i(TAG, "onSkipToPrevious")
                        hub.notifyButton("MEDIA_PREVIOUS", source = "hardware-callback-onPrev")
                    }

                    override fun onStop() {
                        Log.i(TAG, "onStop")
                        hub.notifyButton("MEDIA_STOP", source = "hardware-callback-onStop")
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
                            hub.notifyButton(label, source = "hardware-mediaButtonEvent")
                            return true
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                }
            )
            // Claim: brief PLAYING → PAUSED
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
                    .build()
            )
        }
        mediaSession = session
        Log.i(TAG, "MediaSession attached force=$forceReattach")

        if (forceReattach) {
            pulseExecutor.execute {
                MediaPlaybackPulse.pulse(this)
                mainHandler.post { refreshClaimPlaybackState() }
            }
        }
    }

    private fun refreshClaimPlaybackState() {
        val session = mediaSession ?: return
        val actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                .build()
        )
        session.isActive = true
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
                .build()
        )
        Log.i(TAG, "MediaSession claim refreshed after pulse")
    }

    private fun requestAudioFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = req
            val result = am.requestAudioFocus(req)
            Log.i(TAG, "AudioFocus result=$result")
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                { },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            Log.i(TAG, "AudioFocus(legacy) result=$result")
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
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
        private const val TAG = "BTTestV1-SVC"
        private const val CHANNEL_ID = "bttest_v1_headset"
        private const val NOTIFICATION_ID = 3101
        private const val EXTRA_FORCE_REASSERT = "force_reassert"

        fun start(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun reassert(context: Context) {
            val intent = Intent(context, HeadsetMonitorService::class.java)
                .putExtra(EXTRA_FORCE_REASSERT, true)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeadsetMonitorService::class.java))
        }
    }
}
