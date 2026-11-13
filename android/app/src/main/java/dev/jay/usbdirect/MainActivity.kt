package dev.jay.usbdirect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private val player by lazy { UsbDirectPlayer(this) }

    private var status by mutableStateOf("idle")
    private var devices by mutableStateOf<List<UsbDevice>>(emptyList())
    private var chosenDevice by mutableStateOf<UsbDevice?>(null)
    private var chosenUri by mutableStateOf<Uri?>(null)
    private var rate by mutableStateOf(48000)
    private var bits by mutableStateOf(24)
    private var volume by mutableStateOf(0.6f)
    private var playing by mutableStateOf(false)

    private var pendingAction: (() -> Unit)? = null

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

        val flags = if (Build.VERSION.SDK_INT >= 33)
            Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), flags)

        player.onInfo = { status = it }
        player.onError = { status = "error: $it" }
        player.onFinished = { playing = false; if (status.startsWith("engine")) status = "finished" }

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
                        Text("USB Direct", style = MaterialTheme.typography.headlineSmall)
                        Text(status, style = MaterialTheme.typography.bodySmall)

                        Button(onClick = { refreshDevices() }) { Text("Scan USB devices") }

                        OutlinedButton(onClick = {
                            val report = probeUsbAudio()
                            Log.i("VCProbe", report)
                            status = report
                        }) { Text("Probe USB audio path") }

                        devices.forEach { d ->
                            OutlinedButton(
                                onClick = { selectDevice(d) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${d.productName ?: "device"}  " +
                                    "%04x:%04x".format(d.vendorId, d.productId) +
                                    if (d == chosenDevice) "   [selected]" else ""
                                )
                            }
                        }

                        val picker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                contentResolver.takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                chosenUri = uri
                                status = "file: $uri"
                            }
                        }
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }) {
                            Text(if (chosenUri == null) "Pick audio file" else "Change file")
                        }

                        Text("Sample rate")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(44100, 48000, 96000).forEach { r ->
                                FilterChip(selected = rate == r, onClick = { rate = r },
                                    label = { Text("${r / 1000}") })
                            }
                        }

                        Text("Bit depth")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(16, 24).forEach { b ->
                                FilterChip(selected = bits == b, onClick = { bits = b },
                                    label = { Text("$b") })
                            }
                        }

                        Text("Volume  ${(volume * 100).toInt()}%")
                        Slider(
                            value = volume,
                            onValueChange = {
                                volume = it
                                player.setVolume(it)
                            }
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = chosenDevice != null && chosenUri != null && !playing,
                                onClick = { play() }
                            ) { Text("Play") }

                            OutlinedButton(
                                enabled = playing,
                                onClick = {
                                    player.stop()
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
        devices = usbManager.deviceList.values.toList()
        status = "${devices.size} USB device(s) visible"
    }

    private fun selectDevice(d: UsbDevice) {
        chosenDevice = d
        if (usbManager.hasPermission(d)) {
            status = "selected ${d.productName}"
        } else {
            pendingAction = { status = "selected ${d.productName}" }
            usbManager.requestPermission(
                d,
                PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    /** Answers the one architectural question: does this device offer
     *  MIX_BEHAVIOR_BIT_PERFECT, i.e. can we bypass AudioFlinger's mixer
     *  without root? */
    private fun probeUsbAudio(): String {
        if (Build.VERSION.SDK_INT < 33) return "needs Android 13+"
        val am = getSystemService(AudioManager::class.java) ?: return "no AudioManager"
        val sb = StringBuilder()
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in outs) {
            if (d.type != AudioDeviceInfo.TYPE_USB_HEADSET &&
                d.type != AudioDeviceInfo.TYPE_USB_DEVICE) continue
            sb.append("USB id=${d.id} type=${d.type} name=${d.productName}\n")
            sb.append("  rates=${d.sampleRates.joinToString(",")}\n")
            sb.append("  encodings=${d.encodings.joinToString(",")}\n")
            try {
                val attrs = am.getSupportedMixerAttributes(d)
                if (attrs.isEmpty()) sb.append("  mixer: none\n")
                for (m in attrs) {
                    sb.append("  mixer behavior=${m.mixerBehavior} " +
                              "format=${m.format} rate=${m.sampleRate} " +
                              "mask=0x${m.channelMask.toString(16)}\n")
                }
            } catch (t: Throwable) {
                sb.append("  mixer query failed: ${t.message}\n")
            }
        }
        if (sb.isEmpty()) sb.append("no USB audio output found")
        return sb.toString()
    }

    private fun play() {
        val d = chosenDevice ?: return
        val u = chosenUri ?: return
        playing = true
        status = "starting"
        player.start(d, u, rate, bits, volume)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(permissionReceiver)
        player.stop()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jay.usbdirect.USB_PERMISSION"
    }
}
