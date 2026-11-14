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
    private var transport by mutableStateOf(TransportState())
    private var tracks by mutableStateOf<List<LibTrack>>(emptyList())
    private var albums by mutableStateOf<List<LibAlbum>>(emptyList())
    private var scanState by mutableStateOf("")
    private var scanning by mutableStateOf(false)
    private var dacName by mutableStateOf("")
    private var volume by mutableStateOf(0.2f)
    private var exclusive by mutableStateOf(true)
    private var tab by mutableStateOf(Tab.HOME)
    private var openAlbum by mutableStateOf<LibAlbum?>(null)
    private var folderLabel by mutableStateOf("")
    private var artVersion by mutableStateOf(0)

    private var pendingAlbum: Pair<LibAlbum, Int>? = null
    private var pendingDebug: Track? = null
    private val cancelScan = AtomicBoolean(false)
    private var scanGen = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PlaybackService.LocalBinder).service()
            service = svc
            svc.setVolume(volume)
            svc.setExclusive(exclusive)
            lifecycleScope.launch { svc.state.collect { transport = it } }

            intent?.getStringExtra("debugfile")?.let { path ->
                val f = java.io.File(path)
                if (f.exists()) {
                    val info = MediaProbe.of(this@MainActivity, Uri.fromFile(f))
                    pendingDebug = Track(Uri.fromFile(f),
                        MediaProbe.titleOf(Uri.fromFile(f)), info?.durationMs ?: 0L)
                }
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

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                dacName = "USB permission denied"
                return
            }
            val d = audioDevice() ?: return
            val p = pendingAlbum
            if (p != null) { pendingAlbum = null; startAlbum(p.first, p.second, d) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)

        bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)

        volume = getPreferences(MODE_PRIVATE).getFloat("volume", 0.2f)
        exclusive = getPreferences(MODE_PRIVATE).getBoolean("exclusive", true)
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
        Log.i("VCProbe", "tab extra=" + (intent?.getStringExtra("tab") ?: "none"))
        when (intent?.getStringExtra("tab")) {
            "music" -> tab = Tab.MUSIC
            "output" -> tab = Tab.OUTPUT
            "settings" -> tab = Tab.SETTINGS
        }

        setContent {
            MaterialTheme {
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

                    Scaffold(
                        bottomBar = {
                            Column(Modifier.navigationBarsPadding()) {
                                MiniPlayer(
                                    transport = transport,
                                    volume = volume,
                                    onPlayPause = {
                                        if (transport.playing) service?.pause()
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
                                    }
                                )
                                BottomNav(tab, { tab = it })
                            }
                        }
                    ) { pad ->
                        Box(Modifier.padding(pad).fillMaxSize()) {
                            if (album != null) {
                                AlbumScreen(album) { openAlbum = null }
                            } else when (tab) {
                                Tab.HOME -> HomeScreen(
                                    transport = transport,
                                    trackCount = tracks.size,
                                    albumCount = albums.size,
                                    dacName = dacName,
                                    onShuffleAll = { shuffleAll() },
                                    onPlayPause = {
                                        if (transport.playing) service?.pause()
                                        else service?.resume()
                                    },
                                    onResumeLast = { service?.resume() }
                                )
                                Tab.MUSIC -> MusicScreen(
                                    albums = albums,
                                    scanning = scanning,
                                    scanState = scanState,
                                    artVersion = artVersion,
                                    onOpenAlbum = { openAlbum = it },
                                    onChooseFolder = { picker.launch(null) },
                                    onRescan = {
                                        getPreferences(MODE_PRIVATE).getString("tree", null)
                                            ?.let { scan(Uri.parse(it)) }
                                    }
                                )
                                Tab.OUTPUT -> OutputScreen(
                                    analyzer = service?.player?.analyzer,
                                    transport = transport,
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
                            playAlbum(album, album.tracks.indexOf(t))
                        }
                    )
                }
            }
        }
    }

    /* ---------------------------------------------------------- playback */

    private fun playAlbum(a: LibAlbum, index: Int) {
        val d = audioDevice()
        if (d == null) { dacName = "no USB DAC plugged in"; return }
        if (usbManager.hasPermission(d)) startAlbum(a, index, d)
        else { pendingAlbum = a to index; requestUsb(d) }
    }

    private fun startAlbum(a: LibAlbum, index: Int, d: UsbDevice) {
        val svc = service ?: return
        svc.setDevice(d, 24, volume)
        svc.setQueue(a.tracks.map { it.toQueueTrack() }, index)
    }

    private fun shuffleAll() {
        val d = audioDevice()
        if (d == null) { dacName = "no USB DAC plugged in"; return }
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled().map { it.toQueueTrack() }
        if (!usbManager.hasPermission(d)) { requestUsb(d); return }
        service?.setDevice(d, 24, volume)
        service?.setQueue(shuffled, 0)
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
        runCatching { unbindService(conn) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jay.usbdirect.USB_PERMISSION"
    }
}
