package dev.jay.usbdirect

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/* ------------------------------------------------------------- album */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    album: LibAlbum,
    artVersion: Int,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onTrackMenu: (LibTrack) -> Unit
) {
    val totalMs = album.durationMs
    val codec = album.tracks.firstOrNull()?.codec.orEmpty()
    val bits = album.tracks.firstOrNull()?.bitDepth ?: 0
    val rate = album.tracks.firstOrNull()?.sampleRate ?: 0

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtImage(
                    album.name, album.artist, artVersion,
                    Modifier.fillMaxWidth(0.66f).aspectRatio(1f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    album.name, style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    album.artist, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildList {
                        add(plural(album.tracks.size, "track"))
                        add(fmtTime(totalMs))
                        if (codec.isNotEmpty()) add(codec)
                        if (bits > 0) add("$bits-bit")
                        if (rate > 0) add("${rate / 1000} kHz")
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPlayAll, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                    FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Shuffle, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        itemsIndexed(album.tracks, key = { i, t -> t.uri.toString() + "#" + i }) { i, t ->
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onPlayTrack(i) },
                    onLongClick = { onTrackMenu(t) }
                )
                    .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${i + 1}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (t.bitDepth > 0) "${t.bitDepth}-bit · ${fmtTime(t.durationMs)}"
                        else fmtTime(t.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onTrackMenu(t) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
            }
        }
    }
}

/* ------------------------------------------------------------ artist */

@Composable
fun ArtistDetailScreen(
    name: String,
    albums: List<LibAlbum>,
    tracks: List<LibTrack>,
    artVersion: Int,
    onBack: () -> Unit,
    onOpenAlbum: (LibAlbum) -> Unit,
    onPlayList: (List<LibTrack>, Int) -> Unit,
    onTrackMenu: (LibTrack) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(name, style = MaterialTheme.typography.headlineSmall,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${plural(tracks.size, "track")}  ·  ${plural(albums.size, "album")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
            }
            if (albums.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp)
                ) {
                    items(albums, key = { it.name + "\u0000" + it.artist }) { a ->
                        Column(Modifier.width(120.dp).clickable { onOpenAlbum(a) }) {
                            ArtImage(a.name, a.artist, artVersion,
                                     Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(4.dp))
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            Text(
                "Songs", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
        }
        item {
            // SongList is a LazyColumn itself; give it a bounded height
            Box(Modifier.fillMaxWidth().heightIn(max = 2000.dp)) {
                SongList(tracks, artVersion, onPlayList, onTrackMenu)
            }
        }
    }
}

/* --------------------------------------------------------- favorites */

@Composable
fun FavoritesScreen(
    tracks: List<LibTrack>,
    artVersion: Int,
    onBack: () -> Unit,
    onPlayList: (List<LibTrack>, Int) -> Unit,
    onTrackMenu: (LibTrack) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Favorites", style = MaterialTheme.typography.titleLarge)
        }
        if (tracks.isEmpty()) {
            Text(
                "No favorites yet. Long-press a track or use the ⋮ menu.",
                Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                SongList(tracks, artVersion, onPlayList, onTrackMenu)
            }
        }
    }
}

/* ------------------------------------------------------ output pill */

@Composable
fun OutputPill(
    label: String,
    bitPerfect: Boolean,
    onClick: () -> Unit,
    processed: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (processed) androidx.compose.ui.graphics.Color(0xFF4A3410)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(8.dp).clip(CircleShape).background(
                    when {
                        bitPerfect -> androidx.compose.ui.graphics.Color(0xFF31C48D)
                        processed -> androidx.compose.ui.graphics.Color(0xFFFFD27A)
                        label.startsWith("Phone") -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
