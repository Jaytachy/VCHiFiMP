package dev.jay.usbdirect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Owns playback so it survives the Activity (screen off, task switch).
 *
 * Single source of truth for "is audio playing and what is playing". The
 * Activity binds here and issues commands; the OS sees a mediaPlayback
 * foreground service plus a MediaSession for lock-screen controls.
 */
class PlaybackService : Service() {

    private val binder = LocalBinder()

    lateinit var player: UsbDirectPlayer
        private set

    private lateinit var session: MediaSession
    private var title: String = ""

    @Volatile private var playing = false

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        player = UsbDirectPlayer(this)
        player.onFinished = {
            playing = false
            publishState(PlaybackState.STATE_STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        player.onError = { msg ->
            Log.e("VCProbe", "player error: $msg")
            playing = false
            publishState(PlaybackState.STATE_ERROR)
        }

        session = MediaSession(this, "VCHiFiMP").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stop()
            })
            isActive = true
        }
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ------------------------------------------------------------- commands

    fun play(device: UsbDevice, uri: Uri, rate: Int, bits: Int, volume: Float, label: String) {
        title = label
        startForegroundInternal()
        playing = true
        publishState(PlaybackState.STATE_PLAYING)
        player.start(device, uri, rate, bits, volume)
    }

    /** Pausing closes the device: raw usbfs has no "hold" state, and keeping
     *  the DAC seized while silent would block every other app. */
    fun pause() {
        if (!playing) return
        player.stop()
        playing = false
        publishState(PlaybackState.STATE_PAUSED)
    }

    fun resume() {
        // v1: restart from the beginning; seek support lands with the queue.
        publishState(PlaybackState.STATE_PAUSED)
    }

    fun stop() {
        player.stop()
        playing = false
        publishState(PlaybackState.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun setVolume(v: Float) = player.setVolume(v)

    val isPlaying: Boolean get() = playing

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundInternal() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(title.ifEmpty { "VCHiFiMP" })
            .setContentText("Direct USB output")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
        startForeground(NOTIF_ID, n)
    }

    private fun publishState(state: Int) {
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(pb)
        session.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
        )
    }

    override fun onDestroy() {
        session.isActive = false
        session.release()
        player.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "dev.jay.usbdirect.STOP"
    }
}
