// SPDX-License-Identifier: GPL-3.0-or-later
package dev.jay.usbdirect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    val codec: String,
    /** Exact resume point; 0 on entries written by older builds. */
    val positionMs: Long = 0L,
    /** Queue snapshot for album-contiguous resume; empty on older entries. */
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1
)

data class Track(
    val uri: Uri,
    val title: String,
    val durationMs: Long = 0L,
    val artist: String = "",
    val album: String = "",
    val codec: String = "",
    /** Source bit depth when known; 0 means "probe at play time". */
    val bitDepth: Int = 0,
    /** Source sample rate when known; 0 means "probe at play time". */
    val sampleRate: Int = 0
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
    val error: String? = null,
    /** Source stream metadata, for the signal-path card. */
    val sourceRate: Int = 0,
    val sourceBits: Int = 0,
    /** Output rate/depth actually handed to the DAC. */
    val deviceRate: Int = 0,
    val deviceBits: Int = 0,
    val resampled: Boolean = false,
    /** True when the active sink is AudioTrack/MixerTarget, not raw usbfs. */
    val usingMixer: Boolean = false
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
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    private val _exclusiveState = MutableStateFlow(true)
    val exclusiveState: StateFlow<Boolean> = _exclusiveState.asStateFlow()
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
    private var lastPosPersistMs = 0L
    private var volume = 0.2f
    /** 0 = match each source track, else fixed 16/24. */
    private var bits = 0
    private var exclusive = true
    private var hardwareVolume = false
    @Volatile private var suppressFinish = false
    /** Set when the DAC was unplugged; the next start tries to re-acquire it. */
    @Volatile private var deviceLost = false
    /** True when the user deliberately picked mixer mode; never auto-claim then. */
    @Volatile private var mixerByUser = false
    @Volatile private var gaplessEnabled = true
    @Volatile private var autoReclaim = true

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    /** Supplies a shuffled batch of unplayed library tracks for smart autoplay. */
    var queueRefillProvider: (() -> List<Track>)? = null
    @Volatile private var smartAutoplay = true

    private val _sleepRemaining = MutableStateFlow<Long?>(null)
    val sleepRemaining: StateFlow<Long?> = _sleepRemaining.asStateFlow()
    private var sleepEndsAt = 0L
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
                    // keep the cold-start resume point fresh without touching
                    // the lastPlayed flow every tick
                    if (ms - lastPosPersistMs >= 15_000L) {
                        lastPosPersistMs = ms
                        persistLastPlayedPosition(ms)
                    }
                }
            }
            onFormat = { rate, bits ->
                val c = _state.value.codec
                val src = _state.value.sourceRate
                _state.value = _state.value.copy(
                    format = (if (c.isNotEmpty()) "$c · " else "") +
                             "$bits-bit · ${rate / 1000} kHz",
                    deviceRate = rate,
                    deviceBits = bits,
                    resampled = src > 0 && src != rate
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
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            bits = prefs.getInt("outBits", 0)
            // receiver here is the player being constructed
            setBitDepthPolicy(auto = bits == 0, bits = if (bits == 0) 24 else bits)
            gaplessEnabled = prefs.getBoolean("gapless", true)
            autoReclaim = prefs.getBoolean("autoReclaim", true)
            smartAutoplay = prefs.getBoolean("smartAutoplay", true)
            // inside apply{} the receiver is the player we are constructing
            resamplerQuality = prefs.getInt("srcQuality", NativeResampler.QUALITY_MEDIUM)
            setDither(prefs.getBoolean("dither", true))
            gaplessProvider = { if (gaplessEnabled) peekNextForGapless() else null }
            onBoundary = { idx -> main.post { commitBoundary(idx) } }
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
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------- transport

    fun setDevice(d: UsbDevice?, outputBits: Int = bits, startVolume: Float = volume) {
        device = d
        bits = outputBits
        volume = startVolume
        player.setVolume(startVolume)
        _deviceName.value = d?.productName
        if (d != null) deviceLost = false else exclusive = false
        _exclusiveState.value = exclusive
    }

    /**
     * The DAC was unplugged mid-playback. Freeze on the track that is actually
     * playing: snapshot the audible frame, tear the engine down without letting
     * the "track finished" path run, and forget the device. Pressing Play then
     * resumes this track on the platform mixer, not some earlier one.
     */
    fun onUsbDetached() {
        val pos = player.positionMs()
        val wasPlaying = _state.value.playing
        if (pos > 0 && index >= 0) pausedAtMs = pos
        if (index >= 0) persistLastPlayedPosition(pausedAtMs, emit = true)
        Log.i("VCProbe", "DAC detached: idx=$index title='${_state.value.title}' pos=$pos wasPlaying=$wasPlaying")
        suppressFinish = true
        player.stop()
        suppressFinish = false
        device = null
        exclusive = false
        deviceLost = true
        _deviceName.value = null
        _exclusiveState.value = false
        _state.value = _state.value.copy(
            playing = false,
            paused = wasPlaying || _state.value.paused,
            positionMs = if (pos > 0) pos else _state.value.positionMs,
            error = null
        )
        abandonAudioFocus()
        publish(if (_state.value.paused) PlaybackState.STATE_PAUSED
                else PlaybackState.STATE_NONE)
        if (wasPlaying) startForegroundInternal(_state.value.title)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startAtMs: Long = 0L) {
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
            playAt(0, startAtMs)
        } else {
            queue.addAll(tracks)
            _queueFlow.value = queue.toList()
            _state.value = _state.value.copy(queueSize = queue.size)
            playAt(startIndex, startAtMs)
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

    private fun trackToJson(t: Track): org.json.JSONObject = org.json.JSONObject().apply {
        put("uri", t.uri.toString()); put("title", t.title)
        put("durationMs", t.durationMs); put("artist", t.artist)
        put("album", t.album); put("codec", t.codec)
        put("bitDepth", t.bitDepth); put("sampleRate", t.sampleRate)
    }

    private fun trackFromJson(o: org.json.JSONObject?): Track? {
        if (o == null) return null
        val uri = o.optString("uri")
        if (uri.isEmpty()) return null
        return Track(
            Uri.parse(uri), o.optString("title"), o.optLong("durationMs"),
            o.optString("artist"), o.optString("album"), o.optString("codec"),
            o.optInt("bitDepth"), o.optInt("sampleRate")
        )
    }

    private fun loadLastPlayed() {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString("lastPlayed", null) ?: return
        runCatching {
            val o = org.json.JSONObject(raw)
            val qarr = o.optJSONArray("queue")
            val q = if (qarr != null) {
                (0 until qarr.length()).mapNotNull { trackFromJson(qarr.optJSONObject(it)) }
            } else emptyList()
            _lastPlayed.value = LastPlayed(
                o.getString("uri"), o.getString("title"), o.optString("artist"),
                o.optString("album"), o.optLong("durationMs"), o.optString("codec"),
                o.optLong("positionMs"), q, o.optInt("queueIndex", -1)
            )
        }
    }

    private fun lastPlayedJson(lp: LastPlayed): String = org.json.JSONObject().apply {
        put("uri", lp.uri); put("title", lp.title); put("artist", lp.artist)
        put("album", lp.album); put("durationMs", lp.durationMs); put("codec", lp.codec)
        put("positionMs", lp.positionMs)
        put("queueIndex", lp.queueIndex)
        val arr = org.json.JSONArray()
        for (t in lp.queue) arr.put(trackToJson(t))
        put("queue", arr)
    }.toString()

    private fun saveLastPlayed(lp: LastPlayed) {
        val full = lp.copy(queue = queue.toList(), queueIndex = index)
        _lastPlayed.value = full
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastPlayed", lastPlayedJson(full)).apply()
    }

    /**
     * Store the resume point on disk. [emit] also pushes it to the UI, which is
     * only worth doing on pause/stop; routine progress ticks stay off the flow.
     */
    private fun persistLastPlayedPosition(ms: Long, emit: Boolean = false) {
        val lp = _lastPlayed.value ?: return
        val copy = lp.copy(positionMs = ms.coerceAtLeast(0L))
        if (emit) _lastPlayed.value = copy
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastPlayed", lastPlayedJson(copy)).apply()
    }

    /** Used by Home on a cold start, when no queue exists yet. */
    fun resumeLast() {
        val lp = _lastPlayed.value ?: return
        val startMs = if (lp.durationMs > 0L) {
            lp.positionMs.coerceIn(0L, (lp.durationMs - 1000L).coerceAtLeast(0L))
        } else {
            lp.positionMs.coerceAtLeast(0L)
        }
        if (lp.queue.isNotEmpty()) {
            val idx = lp.queueIndex.coerceIn(0, lp.queue.lastIndex)
            setQueue(lp.queue, idx, startMs)
            return
        }
        setQueue(
            listOf(Track(Uri.parse(lp.uri), lp.title, lp.durationMs, lp.artist, lp.album, lp.codec)),
            0,
            startMs
        )
    }

    fun playAt(i: Int, fromMs: Long = 0L) {
        Log.i("VCProbe", "playAt($i, fromMs=$fromMs)")
        val t = queue.getOrNull(i) ?: return
        // Try the DAC on every start when it isn't claimed yet, not only after
        // an explicit detach. A cold start can miss enumeration and leave us on
        // the mixer; findAttachedDac() already checks USB permission.
        if (device == null && autoReclaim && !mixerByUser) {
            findAttachedDac()?.let {
                Log.i("VCProbe", "re-acquired DAC: ${it.productName}")
                device = it
                exclusive = true
                deviceLost = false
                _deviceName.value = it.productName
                _exclusiveState.value = true
            }
        }
        val d = device
        val mixer = !exclusive || d == null
        val outBits = bitsFor(t)
        index = i
        pausedAtMs = fromMs
        lastPosPersistMs = fromMs
        val codec = t.codec.ifEmpty { MediaProbe.codecOf(t.uri) }
        val probe = if (t.sampleRate <= 0 || t.bitDepth <= 0)
            MediaProbe.of(this, t.uri) else null
        val srcRate = t.sampleRate.takeIf { it > 0 } ?: probe?.sampleRate ?: 0
        val srcBits = t.bitDepth.takeIf { it > 0 } ?: probe?.bitDepth ?: 0
        val srcBitsLabel = if (srcBits == 16) 16 else 24
        _state.value = _state.value.copy(
            playing = true, paused = false, index = i, title = t.title,
            positionMs = fromMs, durationMs = t.durationMs, error = null,
            artist = t.artist, album = t.album, codec = codec, format = "",
            missingUri = null, sourceRate = srcRate, sourceBits = srcBitsLabel,
            deviceRate = 0, deviceBits = outBits, resampled = false,
            usingMixer = mixer
        )
        saveLastPlayed(LastPlayed(t.uri.toString(), t.title, t.artist, t.album,
                                   t.durationMs, codec, fromMs))
        startForegroundInternal(t.title)
        publish(PlaybackState.STATE_PLAYING)
        requestAudioFocus()
        player.start(d, t.uri, 48000, outBits, volume, fromMs,
                     useMixer = mixer, sourceBits = srcBits)
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
        persistLastPlayedPosition(pausedAtMs, emit = true)
        publish(PlaybackState.STATE_PAUSED)
        abandonAudioFocus()
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
            RepeatMode.OFF -> when {
                index + 1 < queue.size -> playAt(index + 1)
                smartAutoplay -> {
                    val extra = queueRefillProvider?.invoke().orEmpty()
                    if (extra.isEmpty()) stop()
                    else {
                        Log.i("VCProbe", "smart autoplay: +${extra.size} tracks")
                        queue.addAll(extra)
                        originalQueue.addAll(extra)
                        _queueFlow.value = queue.toList()
                        _state.value = _state.value.copy(queueSize = queue.size)
                        playAt(index + 1)
                    }
                }
                else -> stop()
            }
        }
    }

    fun stop() {
        suppressFinish = true
        player.stop()
        suppressFinish = false
        pausedAtMs = 0
        _state.value = _state.value.copy(playing = false, paused = false, positionMs = 0)
        persistLastPlayedPosition(0L, emit = true)
        publish(PlaybackState.STATE_STOPPED)
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun setOutputBits(b: Int) {
        bits = when (b) { 16 -> 16; 24 -> 24; else -> 0 }
        setBitDepthPolicy()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("outBits", bits).apply()
    }

    private fun setBitDepthPolicy() {
        player.setBitDepthPolicy(auto = bits == 0, bits = if (bits == 0) 24 else bits)
    }

    /** Output depth for this track under the current policy. */
    private fun bitsFor(t: Track): Int {
        if (bits != 0) return bits
        if (t.bitDepth == 16 || t.bitDepth == 24) return t.bitDepth
        val probed = MediaProbe.of(this, t.uri)?.bitDepth ?: 0
        return if (probed == 16 || probed == 24) probed else 24
    }

    fun outputBits(): Int = bits

    fun setResamplerQuality(q: Int) {
        player.resamplerQuality = q.coerceIn(0, 2)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("srcQuality", q).apply()
    }

    fun resamplerQuality(): Int = player.resamplerQuality

    fun setDither(on: Boolean) {
        player.setDither(on)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("dither", on).apply()
    }

    fun setGapless(on: Boolean) {
        gaplessEnabled = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("gapless", on).apply()
    }

    fun gaplessOn(): Boolean = gaplessEnabled

    fun setAutoReclaim(on: Boolean) {
        autoReclaim = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("autoReclaim", on).apply()
    }

    fun autoReclaimOn(): Boolean = autoReclaim

    /**
     * Sleep timer. [minutes] = 0 cancels. Playback pauses cleanly when the
     * countdown reaches zero; the remaining time is exposed for the UI.
     */
    fun setSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacksAndMessages(null)
        if (minutes <= 0) {
            sleepEndsAt = 0
            _sleepRemaining.value = null
            return
        }
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepRemaining.value = minutes * 60_000L
        val tick = object : Runnable {
            override fun run() {
                val left = sleepEndsAt - System.currentTimeMillis()
                if (left <= 0) {
                    _sleepRemaining.value = null
                    sleepEndsAt = 0
                    Log.i("VCProbe", "sleep timer -> pause")
                    pause()
                } else {
                    _sleepRemaining.value = left
                    sleepHandler.postDelayed(this, 1000)
                }
            }
        }
        sleepHandler.postDelayed(tick, 1000)
    }

    fun sleepMinutesLeft(): Long? = _sleepRemaining.value?.let { (it + 59_999) / 60_000 }

    fun setSmartAutoplay(on: Boolean) {
        smartAutoplay = on
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("smartAutoplay", on).apply()
    }

    /** Restore the Home "continue listening" entry from an imported backup. */
    fun restoreLastPlayed(json: String?) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("lastPlayed", json).apply()
        loadLastPlayed()
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> main.post { onFocusChange(change) } }
            .build()
            .also { focusRequest = it }
        val res = am.requestAudioFocus(req)
        hasFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i("VCProbe", "audio focus request -> $res")
    }

    private fun abandonAudioFocus() {
        val req = focusRequest ?: return
        if (!hasFocus) return
        hasFocus = false
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(req)
        Log.i("VCProbe", "audio focus abandoned")
    }

    /**
     * USB audio bypasses AudioFlinger, so ducking via the Android mixer would
     * not affect what the DAC hears. Loss always means pause.
     */
    private fun onFocusChange(change: Int) {
        Log.i("VCProbe", "audio focus change=$change playing=${_state.value.playing}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                if (_state.value.playing) pause()
        }
    }

    /** true = take the device over (bit-perfect). false = Android's mixer. */
    fun setExclusive(on: Boolean) {
        exclusive = on
        mixerByUser = !on
        _exclusiveState.value = on
    }

    /** Next track in play order, without committing to it yet. */
    private fun peekNextForGapless(): Pair<Int, Track>? {
        if (queue.isEmpty() || index < 0) return null
        val n = when (repeatMode) {
            RepeatMode.ONE -> index
            RepeatMode.ALL -> if (index + 1 < queue.size) index + 1 else 0
            RepeatMode.OFF -> if (index + 1 < queue.size) index + 1 else -1
        }
        if (n < 0 || n >= queue.size) return null
        return n to queue[n]
    }

    /** The seam is now audible: flip metadata without touching the stream. */
    private fun commitBoundary(idx: Int) {
        if (!_state.value.playing) return
        val t = queue.getOrNull(idx) ?: return
        index = idx
        pausedAtMs = 0
        lastPosPersistMs = 0
        val codec = t.codec.ifEmpty { MediaProbe.codecOf(t.uri) }
        val srcRate = t.sampleRate.takeIf { it > 0 } ?: _state.value.sourceRate
        val srcBits = t.bitDepth.takeIf { it > 0 } ?: _state.value.sourceBits
        _state.value = _state.value.copy(
            index = idx, title = t.title, artist = t.artist, album = t.album,
            codec = codec, positionMs = 0, durationMs = t.durationMs,
            missingUri = null, sourceRate = srcRate,
            sourceBits = if (srcBits == 16) 16 else 24,
            resampled = srcRate > 0 && _state.value.deviceRate > 0 &&
                        srcRate != _state.value.deviceRate
        )
        saveLastPlayed(LastPlayed(t.uri.toString(), t.title, t.artist, t.album,
                                   t.durationMs, codec))
        startForegroundInternal(t.title)
        publish(PlaybackState.STATE_PLAYING)
        Log.i("VCProbe", "gapless boundary -> ${t.title}")
    }

    /** A permitted audio-class USB device, or null. */
    private fun findAttachedDac(): UsbDevice? {
        val mgr = getSystemService(Context.USB_SERVICE) as UsbManager
        return mgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }?.takeIf { mgr.hasPermission(it) }
    }

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

    /** Queue helpers used by the UI; they never touch the audio path. */
    fun playNext(track: Track) {
        val at = (index + 1).coerceIn(0, queue.size)
        queue.add(at, track)
        originalQueue.add(track)
        _queueFlow.value = queue.toList()
        _state.value = _state.value.copy(queueSize = queue.size)
    }

    fun enqueue(track: Track) {
        queue.add(track)
        originalQueue.add(track)
        _queueFlow.value = queue.toList()
        _state.value = _state.value.copy(queueSize = queue.size)
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
        val previousIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 4,
            Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT),
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
                Notification.Action.Builder(null, "Previous", previousIntent).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null, if (playing) "Pause" else "Play", toggleIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(null, "Next", nextIntent).build()
            )
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
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
        const val ACTION_PREVIOUS = "dev.jay.usbdirect.PREVIOUS"
        const val ACTION_NEXT = "dev.jay.usbdirect.NEXT"
    }
}
