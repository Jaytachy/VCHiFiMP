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
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    private var usbState by mutableStateOf("no DAC")
    private var volume by mutableStateOf(0.2f)
    private var exclusive by mutableStateOf(true)
    private var openAlbum by mutableStateOf<LibAlbum?>(null)
    private var showAnalyzer by mutableStateOf(false)
    private var pendingAlbum: Pair<LibAlbum, Int>? = null
    private var pendingDebug: Track? = null
    private var pendingDebugTrack: Track? = null

    private val cancelScan = AtomicBoolean(false)
    private var scanGen = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PlaybackService.LocalBinder).service()
            service = svc
            svc.setVolume(volume)
            svc.setExclusive(exclusive)
            intent?.getStringExtra("debugfile")?.let { path ->
                val f = java.io.File(path)
                if (f.exists()) {
                    val info = MediaProbe.of(this@MainActivity, Uri.fromFile(f))
                    pendingDebug = Track(Uri.fromFile(f),
                        MediaProbe.titleOf(Uri.fromFile(f)), info?.durationMs ?: 0L)
                }
            }
            lifecycleScope.launch { svc.state.collect { transport = it } }
            pendingDebug?.let { t ->
                pendingDebug = null
                val d = audioDevice()
                if (d != null) {
                    if (usbManager.hasPermission(d)) {
                        svc.setDevice(d, 24, volume)
                        svc.setQueue(listOf(t), 0)
                    } else {
                        usbManager.requestPermission(
                            d, PendingIntent.getBroadcast(
                                this@MainActivity, 0,
                                Intent(ACTION_USB_PERMISSION),
                                PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                        pendingAlbum = null
                        pendingDebugTrack = t
                    }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                val p = pendingAlbum
                val d = audioDevice()
                if (p != null && d != null) startAlbum(p.first, p.second, d)
            } else {
                usbState = "USB permission denied"
            }
            pendingAlbum = null
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
            val tree = Uri.parse(savedTree)
            val cached = LibraryStore.load(this, tree)
            if (cached != null) {
                // launch is instant: show the cached library, rescan only on demand
                tracks = cached
                albums = LibraryScanner.albums(cached)
                scanState = "${cached.size} tracks, ${albums.size} albums"
            } else {
                scan(tree)
            }
        }

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val album = openAlbum
                    if (showAnalyzer) {
                        BackHandler { showAnalyzer = false }
                        AnalyzerScreen(service?.player?.analyzer) { showAnalyzer = false }
                    } else if (album != null) {
                        BackHandler { openAlbum = null }
                        AlbumScreen(album) { openAlbum = null }
                    } else {
                        LibraryScreen()
                    }
                }
            }
        }
    }

    @Composable
    private fun LibraryScreen() {
        var showNowPlaying by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                getPreferences(MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
                scan(uri)
            }
        }

        Scaffold(
            topBar = {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("VCHiFiMP", style = MaterialTheme.typography.headlineSmall,
                             modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAnalyzer = true }) { Text("Spectrum") }
                    }
                    Text(
                        if (transport.playing) "playing  ${transport.title}"
                        else scanState.ifEmpty { usbState },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            },
            bottomBar = {
                if (transport.title.isNotEmpty()) {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    transport.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    fmt(transport.positionMs) + " / " + fmt(transport.durationMs),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            // timeline: drag to seek, commits only on release so a
                            // seek does not reopen the DAC on every pixel
                            var dragging by remember { mutableStateOf(false) }
                            var dragPos by remember { mutableStateOf(0f) }
                            val dur = transport.durationMs.coerceAtLeast(1L)
                            Slider(
                                value = (if (dragging) dragPos
                                         else transport.positionMs.toFloat())
                                    .coerceIn(0f, dur.toFloat()),
                                valueRange = 0f..dur.toFloat(),
                                onValueChange = { dragging = true; dragPos = it },
                                onValueChangeFinished = {
                                    dragging = false
                                    service?.seekTo(dragPos.toLong())
                                },
                                modifier = Modifier.fillMaxWidth().height(28.dp)
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { service?.previous() }) { Text("|◀") }
                                TextButton(onClick = {
                                    if (transport.playing) service?.pause() else service?.resume()
                                }) { Text(if (transport.playing) "Pause" else "Play") }
                                TextButton(onClick = { service?.next() }) { Text("▶|") }
                                Spacer(Modifier.width(8.dp))
                                Text("vol", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = volume,
                                    onValueChange = {
                                        volume = it
                                        service?.setVolume(it)
                                        getPreferences(MODE_PRIVATE).edit()
                                            .putFloat("volume", it).apply()
                                    },
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                                )
                                Text("${(volume * 100).toInt()}%",
                                     style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                state = rememberLazyListState()
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(null) }) { Text("Choose folder") }
                        OutlinedButton(
                            enabled = !scanning,
                            onClick = { getPreferences(MODE_PRIVATE)
                                .getString("tree", null)?.let { scan(Uri.parse(it)) } }
                        ) { Text("Rescan") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Exclusive DAC", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (exclusive) "bit-perfect, this app owns the device"
                                else "Android mixer, other apps can play",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = exclusive,
                            onCheckedChange = {
                                exclusive = it
                                service?.setExclusive(it)
                                getPreferences(MODE_PRIVATE).edit()
                                    .putBoolean("exclusive", it).apply()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (albums.isEmpty() && !scanning) {
                    item {
                        Text(
                            "Pick the folder that holds your music. " +
                            "It is scanned once; every play goes straight to the DAC.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                items(albums, key = { it.name + it.artist }) { a ->
                    ListItem(
                        headlineContent = { Text(a.name) },
                        supportingContent = { Text("${a.artist}  ·  ${a.tracks.size} tracks") },
                        modifier = Modifier.clickable { openAlbum = a }
                    )
                }
            }
        }
    }

    @Composable
    private fun AlbumScreen(album: LibAlbum, onBack: () -> Unit) {
        Scaffold(
            topBar = {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = onBack) { Text("< Library") }
                    Text(album.name, style = MaterialTheme.typography.titleLarge)
                    Text(album.artist, style = MaterialTheme.typography.bodySmall)
                }
            }
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
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

    private fun playAlbum(a: LibAlbum, index: Int) {
        val d = audioDevice()
        if (d == null) { usbState = "no USB DAC plugged in"; return }
        if (usbManager.hasPermission(d)) startAlbum(a, index, d)
        else {
            pendingAlbum = a to index
            usbManager.requestPermission(
                d, PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun startAlbum(a: LibAlbum, index: Int, d: UsbDevice) {
        val svc = service ?: return
        svc.setDevice(d, 24, volume)
        svc.setQueue(a.tracks.map { it.toQueueTrack() }, index)
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "-:--"
        val t = ms / 1000
        return "%d:%02d".format(t / 60, t % 60)
    }

    private fun audioDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }

    private fun scan(tree: Uri) {
        // Only one scan at a time: a second one would race the first and both
        // would publish partial libraries.
        cancelScan.set(true)
        val gen = ++scanGen
        cancelScan.set(false)
        scanning = true
        scanState = "scanning…"
        Thread {
            val found = LibraryScanner.scan(
                this, tree,
                onProgress = { n, name ->
                    scanState = "scanning  $n  $name"
                },
                cancel = cancelScan
            )
            LibraryStore.save(this, tree, found)
            runOnUiThread {
                if (gen != scanGen) return@runOnUiThread
                tracks = found
                albums = LibraryScanner.albums(found)
                scanning = false
                scanState = "${found.size} tracks, ${albums.size} albums"
                usbState = audioDevice()?.productName?.let { "DAC: $it" } ?: "no DAC"
            }
            val d = audioDevice()
            if (d != null) {
                runOnUiThread { usbState = "DAC: ${d.productName}" }
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
