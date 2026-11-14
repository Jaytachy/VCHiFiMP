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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepeatMode { OFF, ALL, ONE }

/** Persisted so Home can offer "continue listening" after a cold start. */
data class LastPlayed(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val codec: String
)

data class Track(
    val uri: Uri,
    val title: String,
    val durationMs: Long = 0L,
    val artist: String = "",
    val album: String = "",
    val codec: String = ""
)

data class TransportState(
    val playing: Boolean = false,
    val paused: Boolean = false,
    val index: Int = -1,
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val format: String = "",
    val artist: String = "",
    val album: String = "",
    val codec: String = "",
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val missingUri: String? = null,
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
    private val _queueFlow = MutableStateFlow<List<Track>>(emptyList())
    private val _lastPlayed = MutableStateFlow<LastPlayed?>(null)
    val lastPlayed: StateFlow<LastPlayed?> = _lastPlayed.asStateFlow()
    val queueFlow: StateFlow<List<Track>> = _queueFlow.asStateFlow()
    val state: StateFlow<TransportState> = _state.asStateFlow()

    lateinit var player: UsbDirectPlayer
        private set

    private lateinit var session: MediaSession

    private val main = Handler(Looper.getMainLooper())
    private val queue = mutableListOf<Track>()
    private val originalQueue = mutableListOf<Track>()
    private var shuffleEnabled = false
    private var repeatMode = RepeatMode.OFF
    private var index = -1
    private var device: UsbDevice? = null
    private var pausedAtMs = 0L
    private var volume = 0.2f
    private var bits = 24
    private var exclusive = true
    private var hardwareVolume = false
    @Volatile private var suppressFinish = false

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        player = UsbDirectPlayer(this).apply {
            onInfo = { Log.i("VCProbe", "player: $it") }
            onProgress = { ms ->
                // Late callbacks from the dying decode thread must not move the
                // position after pause; that is what made the resume point drift.
                if (_state.value.playing && !suppressFinish) {
                    _state.value = _state.value.copy(positionMs = ms)
                }
            }
            onFormat = { rate, bits ->
                val c = _state.value.codec
                _state.value = _state.value.copy(
                    format = (if (c.isNotEmpty()) "$c · " else "") +
                             "$bits-bit · ${rate / 1000} kHz"
                )
            }
            onFinished = {
                // Advance on the main thread, never on the decode thread: the
                // finish callback fires from inside the player's own thread, and
                // starting the next track there meant stop() joined the calling
                // thread and tore down the engine mid-transition.
                main.post {
                    if (!suppressFinish && _state.value.playing) advanceOrStop()
                }
            }
            onMissing = { uri ->
                main.post { handleMissing(uri.toString()) }
            }
            onError = { msg ->
                Log.e("VCProbe", "player error: $msg")
                _state.value = _state.value.copy(playing = false, error = msg)
            }
        }
        session = MediaSession(this, "VCHiFiMP").apply {
            // without these flags hardware media keys never reach the session
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
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
        loadLastPlayed()
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
        originalQueue.clear()
        originalQueue.addAll(tracks)
        val start = tracks.getOrNull(startIndex) ?: return

        queue.clear()
        if (shuffleEnabled) {
            // keep what the user chose at the head, shuffle the rest
            queue.add(start)
            queue.addAll(tracks.filterIndexed { i, _ -> i != startIndex }.shuffled())
            _queueFlow.value = queue.toList()
            _state.value = _state.value.copy(queueSize = queue.size)
            playAt(0)
        } else {
            queue.addAll(tracks)
            _queueFlow.value = queue.toList()
            _state.value = _state.value.copy(queueSize = queue.size)
            playAt(startIndex)
        }
    }

    /** Shuffling rewrites the active order; the original is kept for turning it off. */
    fun setShuffle(on: Boolean) {
        if (on == shuffleEnabled) return
        shuffleEnabled = on
        val current = queue.getOrNull(index)

        if (on) {
            if (current != null) {
                val rest = queue.filterIndexed { i, _ -> i != index }.shuffled()
                queue.clear()
                queue.add(current)
                queue.addAll(rest)
                index = 0
            }
        } else {
            queue.clear()
            queue.addAll(originalQueue)
            if (current != null) {
                val ni = queue.indexOfFirst { it.uri == current.uri }
                if (ni >= 0) index = ni
            }
        }

        _queueFlow.value = queue.toList()
        _state.value = _state.value.copy(shuffle = on, index = index)
    }

    fun setRepeat(mode: RepeatMode) {
        repeatMode = mode
        _state.value = _state.value.copy(repeat = mode)
    }

    fun cycleRepeat() {
        setRepeat(
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
        )
    }

    private fun loadLastPlayed() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("lastPlayed", null) ?: return
        runCatching {
            val o = org.json.JSONObject(raw)
            _lastPlayed.value = LastPlayed(
                o.getString("uri"), o.getString("title"), o.optString("artist"),
                o.optString("album"), o.optLong("durationMs"), o.optString("codec")
            )
        }
    }

    private fun saveLastPlayed(lp: LastPlayed) {
        _lastPlayed.value = lp
        val o = org.json.JSONObject().apply {
            put("uri", lp.uri); put("title", lp.title); put("artist", lp.artist)
            put("album", lp.album); put("durationMs", lp.durationMs); put("codec", lp.codec)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastPlayed", o.toString()).apply()
    }

    /** Used by Home on a cold start, when no queue exists yet. */
    fun resumeLast() {
        val lp = _lastPlayed.value ?: return
        if (device == null) {
            _state.value = _state.value.copy(error = "no USB DAC selected")
            return
        }
        setQueue(
            listOf(Track(Uri.parse(lp.uri), lp.title, lp.durationMs, lp.artist, lp.album, lp.codec)),
            0
        )
    }

    fun playAt(i: Int, fromMs: Long = 0L) {
        Log.i("VCProbe", "playAt($i, fromMs=$fromMs)")
        val t = queue.getOrNull(i) ?: return
        val d = device ?: run {
            _state.value = _state.value.copy(error = "no USB DAC selected")
            return
        }
        index = i
        pausedAtMs = fromMs
        val codec = t.codec.ifEmpty { MediaProbe.codecOf(t.uri) }
        _state.value = _state.value.copy(
            playing = true, paused = false, index = i, title = t.title,
            positionMs = fromMs, durationMs = t.durationMs, error = null,
            artist = t.artist, album = t.album, codec = codec, format = "",
            missingUri = null
        )
        saveLastPlayed(LastPlayed(t.uri.toString(), t.title, t.artist, t.album,
                                   t.durationMs, codec))
        startForegroundInternal(t.title)
        publish(PlaybackState.STATE_PLAYING)
        player.start(d, t.uri, 48000, bits, volume, fromMs, useMixer = !exclusive)
    }

    fun play() = if (index >= 0) playAt(index, pausedAtMs) else Unit

    fun pause() {
        if (!_state.value.playing) return
        pausedAtMs = _state.value.positionMs
        Log.i("VCProbe", "pause: pos=${_state.value.positionMs} -> pausedAt=$pausedAtMs")
        suppressFinish = true
        _state.value = _state.value.copy(playing = false, paused = true)
        player.stop()
        suppressFinish = false
        publish(PlaybackState.STATE_PAUSED)
        // keep the notification: dropping the foreground state made the whole
        // thing disappear, which is not what a pause means
        startForegroundInternal(_state.value.title)
    }

    fun resume() {
        Log.i("VCProbe", "resume: pausedAt=$pausedAtMs idx=$index")
        if (index >= 0) playAt(index, pausedAtMs)
    }

    fun seekTo(ms: Long) {
        pausedAtMs = ms
        if (_state.value.playing && index >= 0) playAt(index, ms)
        else _state.value = _state.value.copy(positionMs = ms)
    }

    fun next() {
        if (queue.isEmpty()) return
        when {
            index + 1 < queue.size -> playAt(index + 1)
            repeatMode == RepeatMode.ALL -> playAt(0)
            else -> stop()
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        when {
            _state.value.positionMs > 3000 -> playAt(index, 0L)
            index > 0 -> playAt(index - 1)
            repeatMode == RepeatMode.ALL -> playAt(queue.lastIndex)
            else -> playAt(index, 0L)
        }
    }

    /** Natural end of a track: the only place Repeat.ONE differs from a skip. */
    private fun advanceOrStop() {
        when (repeatMode) {
            RepeatMode.ONE -> playAt(index, 0L)
            RepeatMode.ALL -> playAt(if (index + 1 < queue.size) index + 1 else 0)
            RepeatMode.OFF -> if (index + 1 < queue.size) playAt(index + 1) else stop()
        }
    }

    fun stop() {
        suppressFinish = true
        player.stop()
        suppressFinish = false
        pausedAtMs = 0
        _state.value = _state.value.copy(playing = false, paused = false, positionMs = 0)
        publish(PlaybackState.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** true = take the device over (bit-perfect). false = Android's mixer. */
    fun setExclusive(on: Boolean) { exclusive = on }

    /** Drop a vanished file from the queue and keep playing around it. */
    private fun handleMissing(uriString: String) {
        val at = queue.indexOfFirst { it.uri.toString() == uriString }
        queue.removeAll { it.uri.toString() == uriString }
        originalQueue.removeAll { it.uri.toString() == uriString }
        _queueFlow.value = queue.toList()
        _state.value = _state.value.copy(
            queueSize = queue.size, missingUri = uriString,
            error = "file missing — removed from library"
        )
        if (queue.isEmpty()) stop()
        else {
            val next = (if (at >= 0) at else index).coerceIn(0, queue.lastIndex)
            playAt(next)
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (hardwareVolume) {
            val info = player.hwVolumeInfo()
            if (info != null) {
                val raw = info[0] + ((info[1] - info[0]) * volume).toInt()
                if (player.setHwVolume(raw)) {
                    player.setVolume(1f)      // digital unity: stay bit-perfect
                    return
                }
            }
        }
        player.setVolume(volume)
    }

    /** Route the slider through the DAC's Feature Unit instead of float gain. */
    fun setHardwareVolume(on: Boolean) {
        hardwareVolume = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hwvol", on).apply()
        if (on) {
            val info = player.hwVolumeInfo()
            if (info != null) {
                val raw = info[0] + ((info[1] - info[0]) * volume).toInt()
                if (player.setHwVolume(raw)) player.setVolume(1f)
            }
        } else {
            player.setVolume(volume)
        }
    }

    fun hardwareVolumeEnabled(): Boolean = hardwareVolume
    fun hardwareVolumeSupported(): Boolean = player.hwVolumeInfo() != null

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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
        // Speed must be 0 unless actually playing. Publishing 1.0 while paused
        // made the platform extrapolate the position with wall-clock time; its
        // seek-to-then-play round trip then poisoned pausedAtMs with the
        // extrapolated value, which is why resume jumped by the pause duration.
        val speed = if (state == PlaybackState.STATE_PLAYING) 1.0f else 0.0f
        val pos = when (state) {
            PlaybackState.STATE_PAUSED -> pausedAtMs
            PlaybackState.STATE_STOPPED -> 0L
            else -> s.positionMs
        }
        val pb = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, pos, speed)
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
        private const val PREFS = "vc"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "dev.jay.usbdirect.STOP"
        const val ACTION_PAUSE = "dev.jay.usbdirect.PAUSE"
        const val ACTION_RESUME = "dev.jay.usbdirect.RESUME"
    }
}
