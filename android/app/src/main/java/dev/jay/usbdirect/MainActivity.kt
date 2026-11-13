package dev.jay.usbdirect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    private var status by mutableStateOf("idle")
    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var chosenDevice by mutableStateOf<UsbDevice?>(null)
    private var chosenUri by mutableStateOf<Uri?>(null)
    private var bits by mutableStateOf(24)
    private var volume by mutableStateOf(0.6f)
    private var playing by mutableStateOf(false)

    private var service: PlaybackService? by mutableStateOf(null)
    private var autoPlayRequested = false
    private var pendingAction: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as PlaybackService.LocalBinder).service()
            service?.setVolume(volume)
            if (autoPlayRequested) { autoPlayRequested = false; debugAutoPlay() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            status = if (granted) "USB permission granted" else "USB permission denied"
            if (granted) pendingAction?.invoke()
            pendingAction = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        autoPlayRequested = intent?.getBooleanExtra("autoplay", false) == true

        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)

        bindService(
            Intent(this, PlaybackService::class.java),
            connection, Context.BIND_AUTO_CREATE
        )

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("VCHiFiMP", style = MaterialTheme.typography.headlineSmall)
                        Text("Direct USB output", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(status, style = MaterialTheme.typography.bodySmall)

                        OutlinedButton(
                            onClick = { refreshDevices() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Scan USB devices") }

                        devices.forEach { d ->
                            OutlinedButton(
                                onClick = { selectDevice(d) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    (d.productName ?: "device") +
                                    "   %04x:%04x".format(d.vendorId, d.productId) +
                                    if (d == chosenDevice) "   [selected]" else ""
                                )
                            }
                        }

                        val picker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                contentResolver.takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                chosenUri = uri
                                status = "file: ${uri.lastPathSegment}"
                            }
                        }
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Text(if (chosenUri == null) "Pick audio file" else "Change file")
                        }

                        Text("Output depth")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(16, 24).forEach { b ->
                                FilterChip(
                                    selected = bits == b,
                                    onClick = { bits = b },
                                    label = { Text("$b-bit") }
                                )
                            }
                        }

                        Text("Volume  ${(volume * 100).toInt()}%")
                        Slider(
                            value = volume,
                            onValueChange = {
                                volume = it
                                service?.setVolume(it)
                            }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = chosenDevice != null && chosenUri != null && !playing,
                                onClick = { startPlayback() }
                            ) { Text("Play") }

                            OutlinedButton(
                                enabled = playing,
                                onClick = {
                                    service?.stop()
                                    playing = false
                                    status = "stopped"
                                }
                            ) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }

    private fun refreshDevices() {
        devices = usbManager.deviceList.values.filter { d ->
            (0 until d.interfaceCount).any {
                d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }
        }
        status = "${devices.size} USB audio device(s)"
    }

    private fun selectDevice(d: UsbDevice) {
        chosenDevice = d
        withPermission(d) { status = "selected ${d.productName}" }
    }

    private fun withPermission(d: UsbDevice, action: () -> Unit) {
        if (usbManager.hasPermission(d)) { action(); return }
        pendingAction = action
        usbManager.requestPermission(
            d,
            PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    /** Test hook only, used by adb: --ez autoplay true */
    private fun debugAutoPlay() {
        val f = java.io.File(getExternalFilesDir(null), "test.wav")
        if (!f.exists()) { status = "no test.wav"; return }
        refreshDevices()
        val d = devices.firstOrNull() ?: run { status = "no usb audio"; return }
        chosenDevice = d
        chosenUri = Uri.fromFile(f)
        Log.i("VCProbe", "debug autoplay " + f.absolutePath)
        startPlayback()
    }

    private fun startPlayback() {
        val d = chosenDevice ?: return
        val u = chosenUri ?: return
        val svc = service
        if (svc == null) { status = "service not bound yet"; return }
        withPermission(d) {
            playing = true
            status = "playing"
            Log.i("VCProbe", "service play ${u.lastPathSegment}")
            svc.play(d, u, 48000, bits, volume, u.lastPathSegment ?: "track")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unbindService(connection) }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jay.usbdirect.USB_PERMISSION"
    }
}
