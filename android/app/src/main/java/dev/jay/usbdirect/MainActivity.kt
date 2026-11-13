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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    private var openAlbum by mutableStateOf<LibAlbum?>(null)
    private var pendingAlbum: Pair<LibAlbum, Int>? = null

    private val cancelScan = AtomicBoolean(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as PlaybackService.LocalBinder).service()
            service = svc
            lifecycleScope.launch { svc.state.collect { transport = it } }
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

        val savedTree = getPreferences(MODE_PRIVATE).getString("tree", null)
        if (savedTree != null) scan(Uri.parse(savedTree))

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val album = openAlbum
                    if (album != null) {
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
                    Text("VCHiFiMP", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (transport.playing) "playing  ${transport.title}"
                        else if (scanning) scanState else usbState,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            },
            bottomBar = {
                if (transport.title.isNotEmpty()) {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(transport.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${transport.positionMs / 1000}s",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = {
                                if (transport.playing) service?.pause() else service?.resume()
                            }) { Text(if (transport.playing) "Pause" else "Play") }
                            TextButton(onClick = { service?.stop() }) { Text("Stop") }
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
        svc.setDevice(d, 24, 0.6f)
        svc.setQueue(a.tracks.map { it.toQueueTrack() }, index)
    }

    private fun audioDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }

    private fun scan(tree: Uri) {
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
            runOnUiThread {
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
