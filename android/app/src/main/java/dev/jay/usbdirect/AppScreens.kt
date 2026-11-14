package dev.jay.usbdirect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    MUSIC("Music", Icons.Default.List),
    OUTPUT("Output", Icons.Default.PlayArrow),
    SETTINGS("Settings", Icons.Default.Settings)
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "-:--"
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

/* ------------------------------------------------------------------ nav */

@Composable
fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEach { t ->
                val active = t == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(t) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        t.icon, contentDescription = t.label,
                        tint = if (active) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------- mini player */

@Composable
fun MiniPlayer(
    transport: TransportState,
    volume: Float,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit
) {
    if (transport.title.isEmpty()) return

    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val dur = transport.durationMs.coerceAtLeast(1L)

    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transport.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    fmtTime(transport.positionMs) + " / " + fmtTime(transport.durationMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Slider(
                value = (if (dragging) dragPos else transport.positionMs.toFloat())
                    .coerceIn(0f, dur.toFloat()),
                valueRange = 0f..dur.toFloat(),
                onValueChange = { dragging = true; dragPos = it },
                onValueChangeFinished = { dragging = false; onSeek(dragPos.toLong()) },
                modifier = Modifier.fillMaxWidth().height(26.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrev) { Text("|◀") }
                TextButton(onClick = onPlayPause) {
                    Text(if (transport.playing) "Pause" else "Play")
                }
                TextButton(onClick = onNext) { Text("▶|") }
                Spacer(Modifier.width(6.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                Text("${(volume * 100).toInt()}%",
                     style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/* ---------------------------------------------------------------- home */

@Composable
fun HomeScreen(
    transport: TransportState,
    trackCount: Int,
    albumCount: Int,
    dacName: String,
    onShuffleAll: () -> Unit,
    onPlayPause: () -> Unit,
    onResumeLast: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("VCHiFiMP", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (dacName.isEmpty()) "no DAC connected" else dacName,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
        }

        if (transport.title.isNotEmpty()) {
            item {
                Text("Continue listening", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth().clickable { onPlayPause() },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(transport.title, maxLines = 1,
                                 overflow = TextOverflow.Ellipsis,
                                 style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fmtTime(transport.positionMs) + " / " +
                                fmtTime(transport.durationMs),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(if (transport.playing) "Pause" else "Play")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onShuffleAll, enabled = trackCount > 0) {
                    Text("Shuffle all")
                }
                OutlinedButton(onClick = onResumeLast, enabled = transport.title.isNotEmpty()) {
                    Text("Resume")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "$trackCount tracks  ·  $albumCount albums",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/* --------------------------------------------------------------- music */

@Composable
fun MusicScreen(
    albums: List<LibAlbum>,
    scanning: Boolean,
    scanState: String,
    onOpenAlbum: (LibAlbum) -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Text(scanState, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onChooseFolder) { Text("Choose folder") }
                OutlinedButton(onClick = onRescan, enabled = !scanning) { Text("Rescan") }
            }
            Spacer(Modifier.height(16.dp))
        }
        items(albums, key = { it.name + it.artist }) { a ->
            ListItem(
                headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${a.artist}  ·  ${a.tracks.size} tracks") },
                modifier = Modifier.clickable { onOpenAlbum(a) }
            )
        }
    }
}

/* -------------------------------------------------------------- output */

@Composable
fun OutputScreen(
    analyzer: AudioAnalyzer?,
    transport: TransportState,
    dacName: String,
    exclusive: Boolean,
    onExclusive: (Boolean) -> Unit,
    volume: Float,
    onVolume: (Float) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Output", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))

            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(dacName.ifEmpty { "no DAC connected" },
                         style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (transport.title.isEmpty()) "idle"
                        else "${transport.format.ifEmpty { "—" }}  ·  ${transport.title}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exclusive DAC", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exclusive) "bit-perfect, this app owns the device"
                        else "Android mixer, other apps can play",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = exclusive, onCheckedChange = onExclusive)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("vol", style = MaterialTheme.typography.labelMedium)
                Slider(value = volume, onValueChange = onVolume,
                       modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(20.dp))
            Text("Spectrum", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }

        item { SpectrumPanel(analyzer) }
    }
}

/* ------------------------------------------------------------ settings */

@Composable
fun SettingsScreen(
    folderLabel: String,
    trackCount: Int,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onClearCache: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(20.dp))

            Text("Music folder", style = MaterialTheme.typography.titleSmall)
            Text(folderLabel, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onChooseFolder) { Text("Choose folder") }
                OutlinedButton(onClick = onRescan) { Text("Rescan") }
            }

            Spacer(Modifier.height(24.dp))
            Text("Library", style = MaterialTheme.typography.titleSmall)
            Text("$trackCount tracks cached", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClearCache) { Text("Clear cache") }

            Spacer(Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.titleSmall)
            Text(
                "VCHiFiMP — direct USB audio, software volume, bit-perfect when the " +
                "DAC supports the file's rate.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
