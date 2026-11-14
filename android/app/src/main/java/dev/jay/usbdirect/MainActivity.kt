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
import android.media.AudioManager
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
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
    private var darkTheme by mutableStateOf(true)
    private var queueTracks by mutableStateOf<List<Track>>(emptyList())

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
            // needed so Home's "continue listening" can start on a cold launch
            val dac = audioDevice()
            if (dac != null && usbManager.hasPermission(dac)) {
                svc.setDevice(dac, 24, volume)
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
            }
            lifecycleScope.launch { svc.deviceName.collect { dacName = it ?: "" } }
            lifecycleScope.launch { svc.exclusiveState.collect { exclusive = it } }
            lifecycleScope.launch { svc.lastPlayed.collect { lastPlayed = it } }
            lifecycleScope.launch {
                var lastMissing: String? = null
                svc.state.collect { st ->
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
                        svc.setDevice(d, 24, volume)
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
                service?.setDevice(d, 24, volume)
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
        darkTheme = getPreferences(MODE_PRIVATE).getBoolean("dark", true)
        exclusive = getPreferences(MODE_PRIVATE).getBoolean("exclusive", true)
        hardwareVolume = getPreferences(MODE_PRIVATE).getBoolean("hwvol", false)
        if (intent?.getBooleanExtra("mixer", false) == true) exclusive = false

        val savedTree = getPreferences(MODE_PRIVATE).getString("tree", null)
        if (savedTree != null) {
            folderLabel = Uri.decode(savedTree.substringAfterLast(':')).replace("/", " / ")
            val tree = Uri.parse(savedTree)
            val cached = LibraryStore.load(this, tree)
            if (cached != null) {
                tracks = cached
                albums = LibraryScanner.albums(cached)
                scanState = "${cached.size} tracks, ${albums.size} albums"
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
            if (intent?.getBooleanExtra("skipnext", false) == true) service?.next()
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
            if (intent?.getBooleanExtra("skipnext", false) == true) service?.next()
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
            VcTheme(darkTheme) {
                Surface(Modifier.fillMaxSize()) {
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("tree", uri.toString()).apply()
                            folderLabel = Uri.decode(uri.toString().substringAfterLast(':'))
                                .replace("/", " / ")
                            scan(uri)
                        }
                    }

                    val album = openAlbum
                    if (album != null) BackHandler { openAlbum = null }
                    if (showQueue) BackHandler { showQueue = false }
                    if (openArtist != null) BackHandler { openArtist = null }
                    if (showFavorites) BackHandler { showFavorites = false }
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
                            isFavorite = lastPlayed?.uri?.let { favorites.contains(it) } == true,
                            onToggleFavorite = {
                                lastPlayed?.uri?.let { toggleFavorite(it) }
                            }
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
                        AlbumDetailScreen(
                            album = album,
                            artVersion = artVersion,
                            onBack = { openAlbum = null },
                            onPlayAll = { playList(album.tracks, 0) },
                            onShuffle = { playList(album.tracks.shuffled(), 0) },
                            onPlayTrack = { playList(album.tracks, it) },
                            onTrackMenu = { sheetTrack = it }
                        )
                        return@Surface
                    }
                    if (artist != null) {
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
                        return@Surface
                    }
                    if (showFavorites) {
                        FavoritesScreen(
                            tracks = tracks.filter { favorites.contains(it.uri.toString()) },
                            artVersion = artVersion,
                            onBack = { showFavorites = false },
                            onPlayList = { list, i -> playList(list, i) },
                            onTrackMenu = { sheetTrack = it }
                        )
                        return@Surface
                    }

                    Scaffold(
                        bottomBar = {
                            Column(Modifier.navigationBarsPadding()) {
                                MiniPlayer(
                                    transportFlow = service?.state,
                                    volume = volume,
                                    artVersion = artVersion,
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
                                    artVersion = artVersion,
                                    dacName = dacName,
                                    exclusive = exclusive,
                                    onOutput = { tab = Tab.OUTPUT },
                                    favoritesCount = favorites.size,
                                    onFavorites = { showFavorites = true },
                                    onResumeLast = { service?.resumeLast() },
                                    onShuffleAll = { shuffleAll() },
                                    onOpenAlbum = { openAlbum = it },
                                    onShortcut = {
                                        librarySection = it
                                        tab = Tab.MUSIC
                                    }
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
                                    onHardwareVolume = {
                                        hardwareVolume = it
                                        service?.setHardwareVolume(it)
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("hwvol", it).apply()
                                    }
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
                                    darkTheme = darkTheme,
                                    onDarkTheme = {
                                        darkTheme = it
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putBoolean("dark", it).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
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
            service?.setDevice(d, 24, volume)
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
        service?.setDevice(d, 24, volume)
        service?.setExclusive(true)
        getPreferences(MODE_PRIVATE).edit().putBoolean("exclusive", true).apply()
        Log.i("VCProbe", "DAC attached: $dacName")
    }

    private fun requestUsb(d: UsbDevice) {
        usbManager.requestPermission(
            d, PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
        )
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
            val found = LibraryScanner.scan(
                this, tree,
                onProgress = { n, name -> scanState = "scanning  $n  $name" },
                cancel = cancelScan
            )
            LibraryStore.save(this, tree, found)
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
