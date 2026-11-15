package dev.jay.usbdirect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.DocumentsContract
import android.view.WindowManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    private var service: PlaybackService? by mutableStateOf(null)
    private var tracks by mutableStateOf<List<LibTrack>>(emptyList())
    private var albums by mutableStateOf<List<LibAlbum>>(emptyList())
    private var scanState by mutableStateOf("")
    private var scanning by mutableStateOf(false)
    private var dacName by mutableStateOf("")
    private var volume by mutableStateOf(0.2f)
    private var exclusive by mutableStateOf(true)
    private var hardwareVolume by mutableStateOf(false)
    private var tab by mutableStateOf(Tab.HOME)
    private var openAlbum by mutableStateOf<LibAlbum?>(null)
    private var folderLabel by mutableStateOf("")
    private var librarySection by mutableStateOf(LibSection.SONGS)
    private var lastPlayed by mutableStateOf<LastPlayed?>(null)
    private var openArtist by mutableStateOf<String?>(null)
    private var showFavorites by mutableStateOf(false)
    private var sheetTrack by mutableStateOf<LibTrack?>(null)
    private var favorites by mutableStateOf<Set<String>>(emptySet())
    private var artVersion by mutableStateOf(0)
    private var showNowPlaying by mutableStateOf(false)
    private var showQueue by mutableStateOf(false)
    private var themeMode by mutableStateOf(ThemeMode.DARK)
    private var dynamicColors by mutableStateOf(true)
    private var outputBits by mutableStateOf(0)   // 0 = auto (match source)
    private var srcQuality by mutableStateOf(NativeResampler.QUALITY_MEDIUM)
    private var dither by mutableStateOf(true)
    private var gaplessOn by mutableStateOf(true)
    private var autoReclaim by mutableStateOf(true)
    private var skipShort by mutableStateOf(false)
    private var sleepMinutes by mutableStateOf(0)
    private var sleepRemaining by mutableStateOf<Long?>(null)
    private var dynamicMaterial by mutableStateOf(true)
    private var amoled by mutableStateOf(false)
    private var excludedFolders by mutableStateOf<Set<String>>(emptySet())
    private var autoPlayDac by mutableStateOf(false)
    private var keepScreenOn by mutableStateOf(false)
    private var smartAutoplay by mutableStateOf(true)
    private var swipeUpMini by mutableStateOf(true)
    private var showFolderPicker by mutableStateOf(false)
    private var allTracks by mutableStateOf<List<LibTrack>>(emptyList())
    private var backupMessage by mutableStateOf<String?>(null)
    private var playingNow by mutableStateOf(false)
    private var queueTracks by mutableStateOf<List<Track>>(emptyList())

    private var showEq by mutableStateOf(false)
    private var dspEnabled by mutableStateOf(false)
    private var dspConfig by mutableStateOf(DspConfig())
    private var dspPresetName by mutableStateOf("Flat")
    private var dspUserPresets by mutableStateOf<List<EqPreset>>(emptyList())
    private var eqImportMessage by mutableStateOf<String?>(null)
    private var dspPushJob: Job? = null

    private var pendingPlay: Pair<List<Track>, Int>? = null
    private var pendingDebug: Track? = null
    private val cancelScan = AtomicBoolean(false)
    private var scanGen = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {

            val svc = (binder as PlaybackService.LocalBinder).service()
            service = svc
            svc.setVolume(volume)
            svc.setExclusive(exclusive)
            svc.setHardwareVolume(hardwareVolume)
            svc.setOutputBits(outputBits)
            svc.setResamplerQuality(srcQuality)
            svc.setDither(dither)
            svc.setGapless(gaplessOn)
            svc.setAutoReclaim(autoReclaim)
            svc.setSmartAutoplay(smartAutoplay)
            svc.player.setDspConfig(dspConfig)
            svc.player.setDspEnabled(dspEnabled)
            svc.queueRefillProvider = {
                val existing = svc.queueFlow.value.map { it.uri }.toSet()
                allTracks.filter { it.uri !in existing }
                    .shuffled().take(20).map { it.toQueueTrack() }
            }
            // needed so Home's "continue listening" can start on a cold launch
            val dac = audioDevice()
            if (dac != null && usbManager.hasPermission(dac)) {
                svc.setDevice(dac, outputBits, volume)
                dacName = dac.productName ?: "USB DAC"
            } else if (dac != null) {
                // DAC present but no grant yet (fresh install / replug): ask now
                // so the next tap takes the bit-perfect path.
                requestUsb(dac)
            } else if (dac == null) {
                // no DAC: fall back to the phone/Bluetooth mixer silently
                exclusive = false
                svc.setExclusive(false)
                dacName = ""
                // A cold start can race USB enumeration; retry once so the
                // first tap does not commit the queue to the mixer.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val late = audioDevice()
                    if (late != null) {
                        if (usbManager.hasPermission(late)) attachDac(late)
                        else requestUsb(late)
                    }
                }, 500)
            }
            lifecycleScope.launch { svc.deviceName.collect { dacName = it ?: "" } }
            lifecycleScope.launch { svc.exclusiveState.collect { exclusive = it } }
            lifecycleScope.launch { svc.sleepRemaining.collect { sleepRemaining = it } }
            lifecycleScope.launch { svc.lastPlayed.collect { lastPlayed = it } }
            lifecycleScope.launch {
                var lastMissing: String? = null
                svc.state.collect { st ->
                    playingNow = st.playing
                    val m = st.missingUri
                    if (m == null) { lastMissing = null; return@collect }
                    if (m != lastMissing) { lastMissing = m; removeFromLibrary(m) }
                }
            }
            lifecycleScope.launch { svc.queueFlow.collect { queueTracks = it } }

            intent?.getStringExtra("debugfile")?.let { path ->
                val f = java.io.File(path)
                if (f.exists()) {
                    val info = MediaProbe.of(this@MainActivity, Uri.fromFile(f))
                    pendingDebug = Track(Uri.fromFile(f),
                        MediaProbe.titleOf(Uri.fromFile(f)), info?.durationMs ?: 0L)
                }
            }

            if (intent?.getBooleanExtra("gapless", false) == true) {
                val f = java.io.File(getExternalFilesDir(null), "test.wav")
                if (f.exists()) {
                    val uri = Uri.fromFile(f)
                    val info = MediaProbe.of(this@MainActivity, uri)
                    val dur = info?.durationMs ?: 0L
                    val a = LibTrack(uri, "gapless A", "test", "test", 1, dur, info?.sampleRate ?: 96000)
                    val b = LibTrack(uri, "gapless B", "test", "test", 2, dur, info?.sampleRate ?: 96000)
                    Log.i("VCProbe", "gapless test: 2 tracks of $dur ms")
                    playList(listOf(a, b), 0)
                }
            }
            if (intent?.getBooleanExtra("autoplayalbum", false) == true) {
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val debugList: List<LibTrack> =
                    if (intent.getBooleanExtra("crossalbum", false))
                        albums.take(6).mapNotNull { it.tracks.firstOrNull() }
                    else albums.maxByOrNull { it.tracks.size }?.tracks ?: emptyList()
                if (debugList.isNotEmpty()) {
                    Log.i("VCProbe", "debug queue: ${debugList.size} tracks, first=" +
                            "${debugList.first().album}")
                    playList(debugList, 0)
                    if (intent.getBooleanExtra("shuffle", false)) {
                        svc.setShuffle(true)
                        Log.i("VCProbe", "debug shuffle on")
                    }
                    if (intent.getBooleanExtra("repeatone", false)) svc.setRepeat(RepeatMode.ONE)
                    if (intent.getBooleanExtra("skiptest", false)) {
                        val h = android.os.Handler(android.os.Looper.getMainLooper())
                        h.postDelayed({ svc.next() }, 6000)
                        Log.i("VCProbe", "skiptest scheduled")
                    }
                    if (intent.getBooleanExtra("pausetest", false)) {
                        val h = android.os.Handler(android.os.Looper.getMainLooper())
                        h.postDelayed({ svc.pause() }, 8000)
                        h.postDelayed({ svc.resume() }, 22000)
                        Log.i("VCProbe", "pausetest scheduled")
                    }
                }
              }, 700)
            }
            pendingDebug?.let { t ->
                pendingDebug = null
                val d = audioDevice()
                if (d != null) {
                    if (usbManager.hasPermission(d)) {
                        svc.setDevice(d, outputBits, volume)
                        svc.setQueue(listOf(t), 0)
                    } else {
                        requestUsb(d)
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    // The broadcast can arrive before the device shows up in
                    // deviceList, so retry briefly instead of giving up.
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    fun tryAttach(attempt: Int) {
                        val d = audioDevice()
                        if (d == null) {
                            if (attempt < 6) handler.postDelayed({ tryAttach(attempt + 1) }, 250)
                            return
                        }
                        if (usbManager.hasPermission(d)) attachDac(d) else requestUsb(d)
                    }
                    tryAttach(0)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // Freeze on the track that is playing before the engine
                    // notices the device is gone, then drop the stale device.
                    service?.onUsbDetached()
                    service?.setDevice(null)
                    pendingPlay = null
                    dacName = ""
                    exclusive = false
                    Log.i("VCProbe", "DAC detached -> paused on current track")
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            // Output route disappeared (USB, wired headset, Bluetooth): pause
            // instead of blasting the speaker.
            if (service?.state?.value?.playing == true) {
                Log.i("VCProbe", "becoming noisy -> pause")
                service?.pause()
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                dacName = "USB permission denied"
                return
            }
            val d = audioDevice() ?: return
            val p = pendingPlay
            if (p != null) {
                pendingPlay = null
                service?.setDevice(d, outputBits, volume)
                service?.setQueue(p.first, p.second)
            } else {
                attachDac(d)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("detach", false)) {
            Log.i("VCProbe", "debug: simulated DAC detach (new intent)")
            service?.onUsbDetached()
            service?.setDevice(null)
            dacName = ""
            exclusive = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)
        registerReceiver(
            usbEvents,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            flags
        )
        favorites = Favorites.load(this)
        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), flags
        )

        bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)

        volume = getPreferences(MODE_PRIVATE).getFloat("volume", 0.2f)
        themeMode = runCatching {
            ThemeMode.valueOf(getPreferences(MODE_PRIVATE).getString("theme", "DARK")!!)
        }.getOrDefault(ThemeMode.DARK)
        dynamicColors = getPreferences(MODE_PRIVATE).getBoolean("dynColors", true)
        outputBits = getPreferences(MODE_PRIVATE).getInt("outBits", 0)
        srcQuality = getPreferences(MODE_PRIVATE).getInt(
            "srcQuality", NativeResampler.QUALITY_MEDIUM
        )
        dither = getPreferences(MODE_PRIVATE).getBoolean("dither", true)
        gaplessOn = getPreferences(MODE_PRIVATE).getBoolean("gapless", true)
        autoReclaim = getPreferences(MODE_PRIVATE).getBoolean("autoReclaim", true)
        skipShort = getPreferences(MODE_PRIVATE).getBoolean("skipShort", false)
        dynamicMaterial = getPreferences(MODE_PRIVATE).getBoolean("dynamicMaterial", true)
        amoled = getPreferences(MODE_PRIVATE).getBoolean("amoled", false)
        excludedFolders = getPreferences(MODE_PRIVATE)
            .getStringSet("excludedFolders", emptySet())!!.toSet()
        autoPlayDac = getPreferences(MODE_PRIVATE).getBoolean("autoPlayDac", false)
        keepScreenOn = getPreferences(MODE_PRIVATE).getBoolean("keepScreenOn", false)
        smartAutoplay = getPreferences(MODE_PRIVATE).getBoolean("smartAutoplay", true)
        swipeUpMini = getPreferences(MODE_PRIVATE).getBoolean("swipeUpMini", true)
        exclusive = getPreferences(MODE_PRIVATE).getBoolean("exclusive", true)
        hardwareVolume = getPreferences(MODE_PRIVATE).getBoolean("hwvol", false)
        if (intent?.getBooleanExtra("mixer", false) == true) exclusive = false

        dspEnabled = getPreferences(MODE_PRIVATE).getBoolean("dspEnabled", false)
        dspConfig = DspConfig(
            bands = DspJson.bandsFromJson(
                getPreferences(MODE_PRIVATE).getString("dspBands", "[]")
            ),
            preampDb = getPreferences(MODE_PRIVATE).getFloat("dspPreampDb", 0f).toDouble(),
            autoHeadroom = getPreferences(MODE_PRIVATE).getBoolean("dspAutoHeadroom", true)
        )
        dspPresetName = getPreferences(MODE_PRIVATE)
            .getString("dspPresetName", "Flat") ?: "Flat"
        dspUserPresets = DspJson.presetsFromJson(
            getPreferences(MODE_PRIVATE).getString("dspUserPresets", "[]")
        )

        val savedTree = getPreferences(MODE_PRIVATE).getString("tree", null)
        if (savedTree != null) {
            folderLabel = folderLabelOf(savedTree)
            val tree = Uri.parse(savedTree)
            val cached = LibraryStore.load(this, tree)
            if (cached != null) {
                allTracks = cached
                tracks = applyFilters(cached)
                albums = LibraryScanner.albums(tracks)
                scanState = "${tracks.size} tracks, ${albums.size} albums"
            } else {
                scan(tree)
            }
        } else {
            scanState = "pick a folder to start"
        }
        dacName = audioDevice()?.productName ?: ""
        if (intent?.getBooleanExtra("nowplaying", false) == true) showNowPlaying = true
        if (intent?.getBooleanExtra("queue", false) == true) { showNowPlaying = true; showQueue = true }
        Log.i("VCProbe", "tab extra=" + (intent?.getStringExtra("tab") ?: "none"))
        when (intent?.getStringExtra("tab")) {
            "music" -> tab = Tab.MUSIC
            "output" -> tab = Tab.OUTPUT
            "settings" -> tab = Tab.SETTINGS
        }

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (intent?.getBooleanExtra("depthtest", false) == true) {
                val dir = getExternalFilesDir(null)
                val a = java.io.File(dir, "tone24.wav")
                val b = java.io.File(dir, "tone16.wav")
                if (a.exists() && b.exists()) {
                    val ta = Track(Uri.fromFile(a), "depth 24", 8000, "test", "test", "WAV", 24)
                    val tb = Track(Uri.fromFile(b), "depth 16", 8000, "test", "test", "WAV", 16)
                    Log.i("VCProbe", "depth test: 24-bit -> 16-bit, svc=${service != null}")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        service?.setQueue(listOf(ta, tb), 0)
                    }, 1500)
                }
            }
            if (intent?.getBooleanExtra("backup", false) == true) {
                val f = java.io.File(getExternalFilesDir(null), "vchifimp_backup.json")
                runCatching { exportBackup(Uri.fromFile(f)) }
                    .onFailure { Log.e("VCProbe", "backup debug export failed", it) }
            }
            if (intent?.getBooleanExtra("restore", false) == true) {
                val f = java.io.File(getExternalFilesDir(null), "vchifimp_backup.json")
                runCatching { importBackup(Uri.fromFile(f)) }
                    .onFailure { Log.e("VCProbe", "backup debug import failed", it) }
            }
            if (intent?.getBooleanExtra("smarttest", false) == true) {
                val dir = getExternalFilesDir(null)
                val a = java.io.File(dir, "tone16.wav")
                if (a.exists()) {
                    service?.setSmartAutoplay(true)
                    service?.setQueue(
                        listOf(Track(Uri.fromFile(a), "smart tone", 8000, "test", "test", "WAV", 16)),
                        0
                    )
                }
            }
            if (intent?.getBooleanExtra("skipnext", false) == true) service?.next()
            val sleepMin = intent?.getIntExtra("sleepmin", -1) ?: -1
            if (sleepMin >= 0) {
                sleepMinutes = sleepMin
                service?.setSleepTimer(sleepMin)
            }
            if (intent?.getIntExtra("outbits", -1) == 24 || intent?.getIntExtra("outbits", -1) == 16) {
                outputBits = intent!!.getIntExtra("outbits", 24)
                service?.setOutputBits(outputBits)
            }
            if (intent?.getBooleanExtra("playall", false) == true) {
                playList(tracks, intent?.getIntExtra("trackIndex", 0) ?: 0)
            }
            if (intent?.getBooleanExtra("resume", false) == true) service?.resume()
            if (intent?.getBooleanExtra("detach", false) == true) {
                Log.i("VCProbe", "debug: simulated DAC detach")
                service?.onUsbDetached()
                service?.setDevice(null)
                dacName = ""
                exclusive = false
            }
            if (intent?.getBooleanExtra("playall", false) == true) {
                playList(tracks, intent?.getIntExtra("trackIndex", 0) ?: 0)
            }
            if (intent?.getBooleanExtra("resume", false) == true) service?.resume()
            when (intent?.getStringExtra("detail")) {
                "album" -> albums.firstOrNull {
                    ArtStore.existing(this, it.name, it.artist) != null
                }?.let { openAlbum = it }
                "artist" -> tracks.firstOrNull()?.artist?.ifEmpty { null }
                    ?.let { openArtist = it }
                "favorites" -> showFavorites = true
                "sheet" -> tracks.firstOrNull()?.let { sheetTrack = it }
            }
        }, 1200)

        setContent {
            val darkNow = when (themeMode) {
                ThemeMode.SYSTEM -> systemIsDark()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(
                    window, window.decorView
                ).apply {
                    isAppearanceLightStatusBars = !darkNow
                    isAppearanceLightNavigationBars = !darkNow
                }
            }
            androidx.compose.runtime.DisposableEffect(keepScreenOn, playingNow) {
                if (keepScreenOn && playingNow) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            VcTheme(dark = darkNow, dynamic = dynamicMaterial, amoled = amoled) {
                Surface(Modifier.fillMaxSize()) {
                    val exportBackupLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/json")
                    ) { uri ->
                        if (uri != null) runCatching { exportBackup(uri) }
                            .onFailure { backupMessage = "Export failed: ${it.message}" }
                    }
                    val importBackupLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) runCatching { importBackup(uri) }
                            .onFailure { backupMessage = "Import failed: ${it.message}" }
                    }
                    val autoEqLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        if (uri != null) importAutoEq(uri)
                    }

                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("tree", uri.toString()).apply()
                            folderLabel = folderLabelOf(uri.toString())
                            scan(uri)
                        }
                    }

                    val album = openAlbum
                    if (album != null) BackHandler { openAlbum = null }
                    if (showQueue) BackHandler { showQueue = false }
                    if (openArtist != null) BackHandler { openArtist = null }
                    if (showFavorites) BackHandler { showFavorites = false }
                    if (showEq) BackHandler { showEq = false }
                    if (showNowPlaying) BackHandler { showNowPlaying = false }
                    if (showNowPlaying) {
                        NowPlayingScreen(
                            transportFlow = service?.state,
                            volume = volume,
                            onClose = { showNowPlaying = false },
                            onPlayPause = {
                                if (service?.state?.value?.playing == true) service?.pause()
                                else service?.resume()
                            },
                            onPrev = { service?.previous() },
                            onNext = { service?.next() },
                            onSeek = { service?.seekTo(it) },
                            onVolume = {
                                volume = it
                                service?.setVolume(it)
                                getPreferences(MODE_PRIVATE).edit()
                                    .putFloat("volume", it).apply()
                            },
                            showQueue = showQueue,
                            queueTracks = queueTracks,
                            onToggleQueue = { showQueue = !showQueue },
                            onPickQueue = { service?.playAt(it) },
                            onToggleShuffle = {
                                service?.setShuffle(service?.state?.value?.shuffle != true)
                            },
                            onCycleRepeat = { service?.cycleRepeat() },
                            dynamicColors = dynamicColors,
                            isFavorite = lastPlayed?.uri?.let { favorites.contains(it) } == true,
                            onToggleFavorite = {
                                lastPlayed?.uri?.let { toggleFavorite(it) }
                            },
                            exclusive = exclusive,
                            dspEnabled = dspEnabled,
                            hardwareVolume = hardwareVolume,
                            ditherOn = dither
                        )
                        return@Surface
                    }

                    TrackActionSheet(
                        track = sheetTrack,
                        isFavorite = sheetTrack?.uri?.toString()
                            ?.let { favorites.contains(it) } == true,
                        onDismiss = { sheetTrack = null },
                        onPlayNext = {
                            sheetTrack?.let { service?.playNext(it.toQueueTrack()) }
                            sheetTrack = null
                        },
                        onEnqueue = {
                            sheetTrack?.let { service?.enqueue(it.toQueueTrack()) }
                            sheetTrack = null
                        },
                        onToggleFavorite = {
                            sheetTrack?.uri?.toString()?.let { toggleFavorite(it) }
                            sheetTrack = null
                        },
                        onGoToAlbum = {
                            val t = sheetTrack
                            if (t != null) {
                                albums.firstOrNull { it.name == t.album && it.artist == t.artist }
                                    ?.let { openAlbum = it }
                            }
                            sheetTrack = null
                        }
                    )

                    val artist = openArtist
                    if (album != null) {
                        DetailShell {
                            AlbumDetailScreen(
                                album = album,
                                artVersion = artVersion,
                                onBack = { openAlbum = null },
                                onPlayAll = { playList(album.tracks, 0) },
                                onShuffle = { playList(album.tracks.shuffled(), 0) },
                                onPlayTrack = { playList(album.tracks, it) },
                                onTrackMenu = { sheetTrack = it }
                            )
                        }
                        return@Surface
                    }
                    if (artist != null) {
                        DetailShell {
                            ArtistDetailScreen(
                                name = artist,
                                albums = albums.filter { it.artist == artist },
                                tracks = tracks.filter {
                                    it.artist.ifEmpty { "Unknown artist" } == artist
                                },
                                artVersion = artVersion,
                                onBack = { openArtist = null },
                                onOpenAlbum = { openAlbum = it },
                                onPlayList = { list, i -> playList(list, i) },
                                onTrackMenu = { sheetTrack = it }
                            )
                        }
                        return@Surface
                    }
                    if (showFavorites) {
                        DetailShell {
                            FavoritesScreen(
                                tracks = tracks.filter { favorites.contains(it.uri.toString()) },
                                artVersion = artVersion,
                                onBack = { showFavorites = false },
                                onPlayList = { list, i -> playList(list, i) },
                                onTrackMenu = { sheetTrack = it }
                            )
                        }
                        return@Surface
                    }

                    if (showEq) {
                        EqScreen(
                            transportFlow = service?.state,
                            enabled = dspEnabled,
                            config = dspConfig,
                            presetName = dspPresetName,
                            userPresets = dspUserPresets,
                            importMessage = eqImportMessage,
                            onBack = { showEq = false },
                            onEnabled = { applyDspEnabled(it) },
                            onConfig = { applyDspConfig(it) },
                            onPresetName = { applyPresetName(it) },
                            onSavePreset = { name, cfg -> saveUserPreset(name, cfg) },
                            onRenamePreset = { old, new -> renameUserPreset(old, new) },
                            onDeletePreset = { deleteUserPreset(it) },
                            onImport = {
                                autoEqLauncher.launch(
                                    arrayOf("text/plain", "text/*", "*/*")
                                )
                            },
                            onReset = { resetEq() }
                        )
                        return@Surface
                    }

                    Scaffold(
                        bottomBar = {
                            Column(Modifier.navigationBarsPadding()) {
                                AppMiniPlayer()
                                BottomNav(tab, { tab = it })
                            }
                        }
                    ) { pad ->
                        Box(Modifier.padding(pad).fillMaxSize()) {
                            when (tab) {
                                Tab.HOME -> HomeScreen(
                                    transportFlow = service?.state,
                                    lastPlayed = lastPlayed,
                                    albums = albums,
                                    tracks = tracks,
                                    artVersion = artVersion,
                                    dacName = dacName,
                                    exclusive = exclusive,
                                    onOutput = { tab = Tab.OUTPUT },
                                    favoritesCount = favorites.size,
                                    onFavorites = { showFavorites = true },
                                    onResumeLast = { service?.resumeLast() },
                                    onPlayPause = {
                                        val st = service?.state?.value
                                        when {
                                            st?.playing == true -> service?.pause()
                                            st != null && st.title.isNotEmpty() -> service?.resume()
                                            else -> service?.resumeLast()
                                        }
                                    },
                                    onShuffleAll = { shuffleAll() },
                                    onPlayTracks = { list -> if (list.isNotEmpty()) playList(list, 0) },
                                    onOpenAlbum = { openAlbum = it },
                                    dspEnabled = dspEnabled,
                                    hardwareVolume = hardwareVolume,
                                    volume = volume,
                                    ditherOn = dither
                                )
                                Tab.MUSIC -> MusicScreen(
                                    albums = albums,
                                    tracks = tracks,
                                    scanning = scanning,
                                    scanState = scanState,
                                    artVersion = artVersion,
                                    section = librarySection,
                                    onSection = { librarySection = it },
                                    onOpenAlbum = { openAlbum = it },
                                    onOpenArtist = { openArtist = it },
                                    onPlayList = { list, i -> playList(list, i) },
                                    onChooseFolder = { picker.launch(null) },
                                    onRescan = {
                                        getPreferences(MODE_PRIVATE).getString("tree", null)
                                            ?.let { scan(Uri.parse(it)) }
                                    },
                                    onFavorites = { showFavorites = true },
                                    favoritesCount = favorites.size,
                                    onTrackMenu = { sheetTrack = it }
                                )
                                Tab.OUTPUT -> OutputScreen(
                                    analyzer = service?.player?.analyzer,
                                    transportFlow = service?.state,
                                    dacName = dacName,
                                    exclusive = exclusive,
                                    onExclusive = {
                                        exclusive = it
                                        service?.setExclusive(it)
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("exclusive", it).apply()
                                    },
                                    volume = volume,
                                    onVolume = {
                                        volume = it
                                        service?.setVolume(it)
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putFloat("volume", it).apply()
                                    },
                                    player = service?.player,
                                    hardwareVolume = hardwareVolume,
                                    dynamicColors = dynamicColors,
                                    mixerRouteMissing = !exclusive && dacName.isNotEmpty() &&
                                        !hasUsbAudioRoute(),
                                    onHardwareVolume = {
                                        hardwareVolume = it
                                        service?.setHardwareVolume(it)
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("hwvol", it).apply()
                                    },
                                    dspEnabled = dspEnabled,
                                    dspConfig = dspConfig,
                                    dspPresetName = dspPresetName,
                                    ditherOn = dither,
                                    onDspEnabled = { applyDspEnabled(it) },
                                    onOpenEq = { showEq = true }
                                )
                                Tab.SETTINGS -> SettingsScreen(
                                    folderLabel = folderLabel.ifEmpty { "not set" },
                                    trackCount = tracks.size,
                                    onChooseFolder = { picker.launch(null) },
                                    onRescan = {
                                        getPreferences(MODE_PRIVATE).getString("tree", null)
                                            ?.let { scan(Uri.parse(it)) }
                                    },
                                    onClearCache = {
                                        LibraryStore.clear(this@MainActivity)
                                        tracks = emptyList()
                                        albums = emptyList()
                                        scanState = "cache cleared — rescan"
                                    },
                                    themeMode = themeMode,
                                    onThemeMode = {
                                        themeMode = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putString("theme", it.name).apply()
                                    },
                                    dynamicColors = dynamicColors,
                                    onDynamicColors = {
                                        dynamicColors = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("dynColors", it).apply()
                                    },
                                    outputBits = outputBits,
                                    onOutputBits = {
                                        outputBits = it
                                        service?.setOutputBits(it)
                                    },
                                    srcQuality = srcQuality,
                                    onSrcQuality = {
                                        srcQuality = it
                                        service?.setResamplerQuality(it)
                                    },
                                    dither = dither,
                                    onDither = {
                                        dither = it
                                        service?.setDither(it)
                                    },
                                    gapless = gaplessOn,
                                    onGapless = {
                                        gaplessOn = it
                                        service?.setGapless(it)
                                    },
                                    autoReclaim = autoReclaim,
                                    onAutoReclaim = {
                                        autoReclaim = it
                                        service?.setAutoReclaim(it)
                                    },
                                    sleepRemainingMs = sleepRemaining,
                                    sleepMinutes = sleepMinutes,
                                    onSleepMinutes = {
                                        sleepMinutes = it
                                        service?.setSleepTimer(it)
                                    },
                                    version = BuildConfig.VERSION_NAME,
                                    skipShort = skipShort,
                                    onSkipShort = {
                                        skipShort = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("skipShort", it).apply()
                                        tracks = applyFilters(allTracks)
                                        albums = LibraryScanner.albums(tracks)
                                        scanState = "${tracks.size} tracks, ${albums.size} albums"
                                    },
                                    dynamicMaterial = dynamicMaterial,
                                    onDynamicMaterial = {
                                        dynamicMaterial = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("dynamicMaterial", it).apply()
                                    },
                                    amoled = amoled,
                                    onAmoled = {
                                        amoled = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("amoled", it).apply()
                                    },
                                    excludedFolders = excludedFolders,
                                    folderOptions = allTracks.map { folderOf(it.uri) }
                                        .filter { it.isNotEmpty() }.distinct().sorted(),
                                    onToggleFolder = { folder ->
                                        excludedFolders = if (excludedFolders.contains(folder))
                                            excludedFolders - folder else excludedFolders + folder
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putStringSet("excludedFolders", excludedFolders)
                                            .apply()
                                        tracks = applyFilters(allTracks)
                                        albums = LibraryScanner.albums(tracks)
                                        scanState = "${tracks.size} tracks, ${albums.size} albums"
                                    },
                                    autoPlayDac = autoPlayDac,
                                    onAutoPlayDac = {
                                        autoPlayDac = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("autoPlayDac", it).apply()
                                    },
                                    keepScreenOn = keepScreenOn,
                                    onKeepScreenOn = {
                                        keepScreenOn = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("keepScreenOn", it).apply()
                                    },
                                    smartAutoplay = smartAutoplay,
                                    onSmartAutoplay = {
                                        smartAutoplay = it
                                        service?.setSmartAutoplay(it)
                                    },
                                    swipeUpMini = swipeUpMini,
                                    onSwipeUpMini = {
                                        swipeUpMini = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("swipeUpMini", it).apply()
                                    },
                                    onExportBackup = { exportBackupLauncher.launch("vchifimp_backup.json") },
                                    onImportBackup = {
                                        importBackupLauncher.launch(
                                            arrayOf("application/json", "text/plain", "*/*")
                                        )
                                    },
                                    backupMessage = backupMessage
                                )
                            }
                        }
                    }
                }
            }
        }
    }


    /* --------------------------------------------------------------- DSP */

    private fun saveDspPrefs() {
        getPreferences(MODE_PRIVATE).edit()
            .putBoolean("dspEnabled", dspEnabled)
            .putFloat("dspPreampDb", dspConfig.preampDb.toFloat())
            .putBoolean("dspAutoHeadroom", dspConfig.autoHeadroom)
            .putString("dspBands", DspJson.bandsToJson(dspConfig.bands).toString())
            .putString("dspPresetName", dspPresetName)
            .putString("dspUserPresets", DspJson.presetsToJson(dspUserPresets).toString())
            .apply()
    }

    /** 30 ms debounce for slider drags; immediate for toggles/presets. */
    private fun scheduleDspPush(immediate: Boolean = false) {
        dspPushJob?.cancel()
        dspPushJob = lifecycleScope.launch {
            if (!immediate) delay(30)
            val p = service?.player ?: return@launch
            p.setDspConfig(dspConfig)
            p.setDspEnabled(dspEnabled)
        }
    }

    private fun applyDspConfig(config: DspConfig, preset: String? = null,
                               immediate: Boolean = false) {
        dspConfig = config
        if (preset != null) dspPresetName = preset
        saveDspPrefs()
        scheduleDspPush(immediate)
    }

    private fun applyDspEnabled(on: Boolean) {
        dspEnabled = on
        saveDspPrefs()
        scheduleDspPush(immediate = true)
    }

    private fun applyPresetName(name: String) {
        dspPresetName = name
        saveDspPrefs()
    }

    private fun saveUserPreset(name: String, config: DspConfig) {
        val list = dspUserPresets.toMutableList()
        val at = list.indexOfFirst { it.name == name }
        if (at >= 0) list[at] = EqPreset(name, config)
        else list.add(EqPreset(name, config))
        dspUserPresets = list
        dspConfig = config
        dspPresetName = name
        saveDspPrefs()
    }

    private fun renameUserPreset(old: String, new: String) {
        if (old == new || new.isBlank()) return
        dspUserPresets = dspUserPresets.map {
            if (it.name == old) EqPreset(new, it.config) else it
        }
        if (dspPresetName == old) dspPresetName = new
        saveDspPrefs()
    }

    private fun deleteUserPreset(name: String) {
        dspUserPresets = dspUserPresets.filterNot { it.name == name }
        if (dspPresetName == name) {
            dspConfig = DspPresets.Flat
            dspPresetName = "Flat"
            scheduleDspPush(immediate = true)
        }
        saveDspPrefs()
    }

    private fun resetEq() {
        dspConfig = DspPresets.Flat
        dspPresetName = "Flat"
        eqImportMessage = null
        saveDspPrefs()
        scheduleDspPush(immediate = true)
    }

    private fun importAutoEq(uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()
                ?.use { it.readText() } ?: error("cannot read file")
            val parsed = AutoEqParser.parse(text)
            if (parsed.bands.isEmpty() && parsed.preampDb == null)
                error("no filters found")
            val name = uri.lastPathSegment?.substringAfterLast('/')
                ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "AutoEQ"
            dspConfig = DspConfig(
                bands = parsed.bands,
                preampDb = parsed.preampDb ?: dspConfig.preampDb,
                autoHeadroom = true
            )
            dspPresetName = name
            eqImportMessage = if (parsed.ignored > 0) {
                "${parsed.ignored} filters ignored"
            } else {
                "Imported ${parsed.bands.size} filters"
            }
            saveDspPrefs()
            scheduleDspPush(immediate = true)
        }.onFailure {
            eqImportMessage = "Import failed: ${it.message}"
        }
    }

    /* ------------------------------------------------------ backup/restore */

    private fun exportBackup(uri: Uri) {
        val settings = org.json.JSONObject().apply {
            put("theme", themeMode.name)
            put("dynamicMaterial", dynamicMaterial)
            put("amoled", amoled)
            put("artColors", dynamicColors)
            put("outBits", outputBits)
            put("srcQuality", srcQuality)
            put("dither", dither)
            put("gapless", gaplessOn)
            put("autoReclaim", autoReclaim)
            put("skipShort", skipShort)
            put("autoPlayDac", autoPlayDac)
            put("keepScreenOn", keepScreenOn)
            put("smartAutoplay", smartAutoplay)
            put("swipeUpMini", swipeUpMini)
            put("sleepMinutes", sleepMinutes)
            put("volume", volume.toDouble())
            put("hardwareVolume", hardwareVolume)
            put("exclusive", exclusive)
            put("dspEnabled", dspEnabled)
            put("dspPreampDb", dspConfig.preampDb)
            put("dspAutoHeadroom", dspConfig.autoHeadroom)
            put("dspBands", DspJson.bandsToJson(dspConfig.bands))
            put("dspPresetName", dspPresetName)
            put("dspUserPresets", DspJson.presetsToJson(dspUserPresets))
        }
        val root = org.json.JSONObject().apply {
            put("app", "VCHiFiMP")
            put("version", BuildConfig.VERSION_NAME)
            put("exportedAt", System.currentTimeMillis())
            put("settings", settings)
            put("favorites", org.json.JSONArray(favorites.toList()))
            put("excludedFolders", org.json.JSONArray(excludedFolders.toList()))
            put("lastPlayed", getSharedPreferences("vc", MODE_PRIVATE)
                .getString("lastPlayed", null))
        }
        contentResolver.openOutputStream(uri)?.use {
            it.write(root.toString(2).toByteArray())
        } ?: throw IllegalStateException("cannot open backup destination")
        backupMessage = "Backup exported"
        Log.i("VCProbe", "backup exported ($uri)")
    }

    private fun importBackup(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()
            ?.use { it.readText() } ?: return
        val root = org.json.JSONObject(text)
        val st = root.optJSONObject("settings") ?: org.json.JSONObject()
        fun bool(k: String, d: Boolean) = if (st.has(k)) st.getBoolean(k) else d
        fun int(k: String, d: Int) = if (st.has(k)) st.getInt(k) else d

        themeMode = runCatching { ThemeMode.valueOf(st.optString("theme", themeMode.name)) }
            .getOrDefault(themeMode)
        dynamicMaterial = bool("dynamicMaterial", dynamicMaterial)
        amoled = bool("amoled", amoled)
        dynamicColors = bool("artColors", dynamicColors)
        outputBits = int("outBits", outputBits)
        srcQuality = int("srcQuality", srcQuality)
        dither = bool("dither", dither)
        gaplessOn = bool("gapless", gaplessOn)
        autoReclaim = bool("autoReclaim", autoReclaim)
        skipShort = bool("skipShort", skipShort)
        autoPlayDac = bool("autoPlayDac", autoPlayDac)
        keepScreenOn = bool("keepScreenOn", keepScreenOn)
        smartAutoplay = bool("smartAutoplay", smartAutoplay)
        swipeUpMini = bool("swipeUpMini", swipeUpMini)
        sleepMinutes = int("sleepMinutes", sleepMinutes)
        volume = if (st.has("volume")) st.getDouble("volume").toFloat() else volume
        hardwareVolume = bool("hardwareVolume", hardwareVolume)
        exclusive = bool("exclusive", exclusive)

        // Additive: old backups simply have none of these keys.
        dspEnabled = bool("dspEnabled", dspEnabled)
        val dspBandsArr = st.optJSONArray("dspBands")
        dspConfig = DspConfig(
            bands = dspBandsArr?.let { DspJson.bandsFromJson(it.toString()) }
                ?: dspConfig.bands,
            preampDb = if (st.has("dspPreampDb")) st.getDouble("dspPreampDb")
                       else dspConfig.preampDb,
            autoHeadroom = bool("dspAutoHeadroom", dspConfig.autoHeadroom)
        )
        dspPresetName = st.optString("dspPresetName", dspPresetName)
        dspUserPresets = st.optJSONArray("dspUserPresets")
            ?.let { DspJson.presetsFromJson(it.toString()) } ?: dspUserPresets

        favorites = root.optJSONArray("favorites")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        } ?: favorites
        Favorites.save(this, favorites)

        excludedFolders = root.optJSONArray("excludedFolders")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        } ?: excludedFolders

        getPreferences(MODE_PRIVATE).edit().apply {
            putString("theme", themeMode.name)
            putBoolean("dynamicMaterial", dynamicMaterial)
            putBoolean("amoled", amoled)
            putBoolean("dynColors", dynamicColors)
            putInt("outBits", outputBits)
            putInt("srcQuality", srcQuality)
            putBoolean("dither", dither)
            putBoolean("gapless", gaplessOn)
            putBoolean("autoReclaim", autoReclaim)
            putBoolean("skipShort", skipShort)
            putBoolean("autoPlayDac", autoPlayDac)
            putBoolean("keepScreenOn", keepScreenOn)
            putBoolean("smartAutoplay", smartAutoplay)
            putBoolean("swipeUpMini", swipeUpMini)
            putStringSet("excludedFolders", excludedFolders)
            putFloat("volume", volume)
            putBoolean("hwvol", hardwareVolume)
            putBoolean("exclusive", exclusive)
            putBoolean("dspEnabled", dspEnabled)
            putFloat("dspPreampDb", dspConfig.preampDb.toFloat())
            putBoolean("dspAutoHeadroom", dspConfig.autoHeadroom)
            putString("dspBands", DspJson.bandsToJson(dspConfig.bands).toString())
            putString("dspPresetName", dspPresetName)
            putString("dspUserPresets", DspJson.presetsToJson(dspUserPresets).toString())
            apply()
        }

        service?.apply {
            setOutputBits(outputBits)
            setResamplerQuality(srcQuality)
            setDither(dither)
            setGapless(gaplessOn)
            setAutoReclaim(autoReclaim)
            setSmartAutoplay(smartAutoplay)
            setVolume(volume)
            setHardwareVolume(hardwareVolume)
            setExclusive(exclusive)
            setSleepTimer(sleepMinutes)
            player.setDspConfig(dspConfig)
            player.setDspEnabled(dspEnabled)
            restoreLastPlayed(
                root.optString("lastPlayed").takeIf { it.isNotBlank() && it != "null" }
            )
        }

        tracks = applyFilters(allTracks)
        albums = LibraryScanner.albums(tracks)
        scanState = "${tracks.size} tracks, ${albums.size} albums"
        backupMessage = "Backup restored"
        Log.i("VCProbe", "backup imported ($uri)")
    }

    /* ------------------------------------------------------------- albums */

    @Composable
    private fun AlbumScreen(album: LibAlbum, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ Library") }
                Column(Modifier.weight(1f)) {
                    Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.titleMedium)
                    Text(album.artist, style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn {
                items(album.tracks, key = { it.uri.toString() }) { t ->
                    ListItem(
                        headlineContent = {
                            Text(
                                (if (t.trackNo > 0) "%02d  ".format(t.trackNo) else "") + t.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text("${t.durationMs / 1000}s" +
                                 if (t.sampleRate > 0) "  ·  ${t.sampleRate / 1000} kHz" else "")
                        },
                        modifier = Modifier.clickable {
                            playList(album.tracks, album.tracks.indexOf(t))
                        }
                    )
                }
            }
        }
    }

    /** Shared mini player so detail routes keep transport visible. */
    @Composable
    private fun AppMiniPlayer() {
        MiniPlayer(
            transportFlow = service?.state,
            volume = volume,
            artVersion = artVersion,
            swipeUpToExpand = swipeUpMini,
            onPlayPause = {
                if (service?.state?.value?.playing == true) service?.pause()
                else service?.resume()
            },
            onPrev = { service?.previous() },
            onNext = { service?.next() },
            onSeek = { service?.seekTo(it) },
            onVolume = {
                volume = it
                service?.setVolume(it)
                getPreferences(MODE_PRIVATE).edit()
                    .putFloat("volume", it).apply()
            },
            onExpand = { showNowPlaying = true }
        )
    }

    /** Detail routes keep the mini player but omit the bottom navigation. */
    @Composable
    private fun DetailShell(content: @Composable () -> Unit) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    AppMiniPlayer()
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) { content() }
        }
    }

    /* ---------------------------------------------------------- playback */

    private fun playList(list: List<LibTrack>, index: Int) {
        if (list.isEmpty()) { Log.i("VCProbe", "playList: empty"); return }
        val q = list.map { it.toQueueTrack() }
        val d = audioDevice()
        if (d != null && !usbManager.hasPermission(d)) {
            // DAC is there but the grant is missing (fresh install / replug).
            pendingPlay = q to index
            requestUsb(d)
            return
        }
        routeTo(d)
        service?.setQueue(q, index)
    }

    private fun shuffleAll() {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled().map { it.toQueueTrack() }
        val d = audioDevice()
        if (d != null && !usbManager.hasPermission(d)) {
            pendingPlay = shuffled to 0
            requestUsb(d)
            return
        }
        routeTo(d)
        service?.setQueue(shuffled, 0)
    }

    /** Point the service at a DAC when there is a usable one, else at the mixer. */
    private fun routeTo(d: UsbDevice?) {
        if (d != null) {
            dacName = d.productName ?: "USB DAC"
            exclusive = true
            service?.setDevice(d, outputBits, volume)
            service?.setExclusive(true)
        } else {
            dacName = ""
            exclusive = false
            service?.setExclusive(false)
        }
    }

    private fun toggleFavorite(uri: String) {
        favorites = if (favorites.contains(uri)) favorites - uri else favorites + uri
        Favorites.save(this, favorites)
    }

    private fun attachDac(d: UsbDevice) {
        dacName = d.productName ?: "USB DAC"
        exclusive = true
        val wasPaused = service?.state?.value?.paused == true
        service?.setDevice(d, outputBits, volume)
        service?.setExclusive(true)
        getPreferences(MODE_PRIVATE).edit().putBoolean("exclusive", true).apply()
        Log.i("VCProbe", "DAC attached: $dacName (autoPlay=$autoPlayDac wasPaused=$wasPaused)")
        if (autoPlayDac && wasPaused) {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ service?.resume() }, 400)
        }
    }

    private fun requestUsb(d: UsbDevice) {
        usbManager.requestPermission(
            d, PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    /** Folder a track lives in, from the SAF document id ("Music/Album"). */
    private fun folderOf(uri: Uri): String {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        val path = id.substringAfter(':', id).trimStart('/')
        return path.substringBeforeLast('/', "")
    }

    /** Short-track and excluded-folder filters applied to every library view. */
    private fun applyFilters(src: List<LibTrack>): List<LibTrack> {
        var out = src
        if (skipShort) out = out.filter { it.durationMs >= 30_000 }
        if (excludedFolders.isNotEmpty()) out = out.filter { t ->
            val f = folderOf(t.uri)
            excludedFolders.none { f == it || f.startsWith("$it/") }
        }
        return out
    }

    /** True when Android's audio stack currently exposes a USB output. */
    private fun hasUsbAudioRoute(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
    }

    /** "primary:Music/Albums" -> "Albums"; keeps the settings row readable. */
    private fun folderLabelOf(tree: String): String {
        val decoded = Uri.decode(tree).trimEnd('/')
        val name = decoded.substringAfterLast(':').substringAfterLast('/')
        return name.ifEmpty { "Music" }
    }

    private fun audioDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }

    /** A track vanished on disk: drop it from the in-memory library and cache. */
    private fun removeFromLibrary(uriString: String) {
        val before = tracks.size
        allTracks = allTracks.filterNot { it.uri.toString() == uriString }
        tracks = tracks.filterNot { it.uri.toString() == uriString }
        if (tracks.size == before) return
        albums = LibraryScanner.albums(tracks)
        getPreferences(MODE_PRIVATE).getString("tree", null)?.let {
            LibraryStore.save(this, Uri.parse(it), tracks)
        }
        scanState = "${tracks.size} tracks, ${albums.size} albums"
        Log.i("VCProbe", "removed missing track from cache: $uriString")
    }

    /* ------------------------------------------------------------- scan */

    private fun scan(tree: Uri) {
        cancelScan.set(true)
        val gen = ++scanGen
        cancelScan.set(false)
        scanning = true
        scanState = "scanning…"
        Thread {
            val raw = LibraryScanner.scan(
                this, tree,
                onProgress = { n, name -> scanState = "scanning  $n  $name" },
                cancel = cancelScan
            )
            allTracks = raw
            val found = applyFilters(raw)
            // the cache keeps the unfiltered scan so folder/short-track filters
            // can be changed without re-walking SAF
            LibraryStore.save(this, tree, raw)
            val grouped = LibraryScanner.albums(found)

            // publish first, then fill in artwork behind it
            runOnUiThread {
                if (gen != scanGen) return@runOnUiThread
                tracks = found
                albums = grouped
                scanning = false
                scanState = "${found.size} tracks, ${grouped.size} albums"
                dacName = audioDevice()?.productName ?: ""
            }

            var done = 0
            for (a in grouped) {
                if (cancelScan.get() || gen != scanGen) break
                val first = a.tracks.firstOrNull() ?: continue
                ArtStore.extract(this, a.name, a.artist, first.uri)
                done++
                if (done % 6 == 0) runOnUiThread {
                    if (gen == scanGen) {
                        artVersion++
                        scanState = "artwork  $done/${grouped.size}"
                    }
                }
            }
            runOnUiThread {
                if (gen == scanGen) {
                    artVersion++
                    scanState = "${found.size} tracks, ${grouped.size} albums"
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelScan.set(true)
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(usbEvents) }
        runCatching { unregisterReceiver(noisyReceiver) }
        runCatching { unbindService(conn) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jay.usbdirect.USB_PERMISSION"
    }
}
