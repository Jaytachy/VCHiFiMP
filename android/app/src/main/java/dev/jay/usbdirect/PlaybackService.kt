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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Track(val uri: Uri, val title: String, val durationMs: Long = 0L)

data class TransportState(
    val playing: Boolean = false,
    val paused: Boolean = false,
    val index: Int = -1,
    val title: String = "",
    val positionMs: Long = 0L,
    val queueSize: Int = 0,
    val error: String? = null
)

/**
 * Owns playback and the queue so it survives the Activity.
 *
 * Pausing releases the USB device rather than holding it open and silent:
 * a seized DAC blocks every other app, and raw usbfs has no "idle" state.
 * Resuming reopens the device and seeks back to the exact frame.
 */
class PlaybackService : Service() {

    private val binder = LocalBinder()
    private val _state = MutableStateFlow(TransportState())
    val state: StateFlow<TransportState> = _state.asStateFlow()

    lateinit var player: UsbDirectPlayer
        private set

    private lateinit var session: MediaSession

    private val queue = mutableListOf<Track>()
    private var index = -1
    private var device: UsbDevice? = null
    private var pausedAtMs = 0L
    private var volume = 0.6f
    private var bits = 24

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        player = UsbDirectPlayer(this).apply {
            onInfo = { Log.i("VCProbe", "player: $it") }
            onProgress = { ms -> _state.value = _state.value.copy(positionMs = ms) }
            onFinished = {
                if (_state.value.playing) advanceOrStop()
            }
            onError = { msg ->
                Log.e("VCProbe", "player error: $msg")
                _state.value = _state.value.copy(playing = false, error = msg)
            }
        }
        session = MediaSession(this, "VCHiFiMP").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            isActive = true
        }
        createChannel()
        publish(PlaybackState.STATE_STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stop()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------- transport

    fun setDevice(d: UsbDevice, outputBits: Int, startVolume: Float) {
        device = d
        bits = outputBits
        volume = startVolume
        player.setVolume(startVolume)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(tracks)
        _state.value = _state.value.copy(queueSize = queue.size)
        if (startIndex in queue.indices) playAt(startIndex)
    }

    fun playAt(i: Int, fromMs: Long = 0L) {
        val t = queue.getOrNull(i) ?: return
        val d = device ?: run {
            _state.value = _state.value.copy(error = "no USB DAC selected")
            return
        }
        index = i
        pausedAtMs = fromMs
        _state.value = _state.value.copy(
            playing = true, paused = false, index = i, title = t.title,
            positionMs = fromMs, error = null
        )
        startForegroundInternal(t.title)
        publish(PlaybackState.STATE_PLAYING)
        player.start(d, t.uri, 48000, bits, volume, fromMs)
    }

    fun play() = if (index >= 0) playAt(index, pausedAtMs) else Unit

    fun pause() {
        if (!_state.value.playing) return
        pausedAtMs = _state.value.positionMs
        player.stop()
        _state.value = _state.value.copy(playing = false, paused = true)
        publish(PlaybackState.STATE_PAUSED)
        // keep the notification: dropping the foreground state made the whole
        // thing disappear, which is not what a pause means
        startForegroundInternal(_state.value.title)
    }

    fun resume() {
        if (index >= 0) playAt(index, pausedAtMs)
    }

    fun seekTo(ms: Long) {
        pausedAtMs = ms
        if (_state.value.playing && index >= 0) playAt(index, ms)
        else _state.value = _state.value.copy(positionMs = ms)
    }

    fun next() {
        if (index + 1 < queue.size) playAt(index + 1)
        else stop()
    }

    fun previous() {
        if (_state.value.positionMs > 3000) playAt(index, 0L)
        else if (index > 0) playAt(index - 1)
        else playAt(index, 0L)
    }

    private fun advanceOrStop() {
        if (index + 1 < queue.size) playAt(index + 1) else stop()
    }

    fun stop() {
        player.stop()
        pausedAtMs = 0
        _state.value = _state.value.copy(playing = false, paused = false, positionMs = 0)
        publish(PlaybackState.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        player.setVolume(volume)
    }

    // ---------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startForegroundInternal(title: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val playing = _state.value.playing

        val toggleIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java)
                .setAction(if (playing) ACTION_PAUSE else ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(title.ifEmpty { "VCHiFiMP" })
            .setContentText(if (playing) "Direct USB output" else "Paused")
            .setSmallIcon(if (playing) android.R.drawable.ic_media_pause
                          else android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(
                Notification.Action.Builder(
                    null, if (playing) "Pause" else "Play", toggleIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()

        if (playing) startForeground(NOTIF_ID, n)
        else getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun publish(state: Int) {
        val s = _state.value
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, s.positionMs, 1.0f)
            .build()
        session.setPlaybackState(pb)
        val track = queue.getOrNull(s.index)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, track?.durationMs ?: 0L)
                .build()
        )
    }

    override fun onDestroy() {
        player.stop()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "dev.jay.usbdirect.STOP"
        const val ACTION_PAUSE = "dev.jay.usbdirect.PAUSE"
        const val ACTION_RESUME = "dev.jay.usbdirect.RESUME"
    }
}
