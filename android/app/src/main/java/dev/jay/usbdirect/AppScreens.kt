package dev.jay.usbdirect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    MUSIC("Music", Icons.Default.List),
    OUTPUT("Output", Icons.Default.PlayArrow),
    SETTINGS("Settings", Icons.Default.Settings)
}

/**
 * Reading playback state only where it is displayed. Collecting it at the top
 * of the tree recomposed the album grid four times a second, which is exactly
 * the kind of thing that makes a scroll feel heavy.
 */
@Composable
fun rememberTransport(flow: StateFlow<TransportState>?): TransportState {
    val f = remember(flow) { flow ?: flowOf(TransportState()) }
    return f.collectAsState(TransportState()).value
}

fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

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
    transportFlow: StateFlow<TransportState>?,
    volume: Float,
    artVersion: Int = 0,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onExpand: () -> Unit = {}
) {
    val transport = rememberTransport(transportFlow)
    if (transport.title.isEmpty()) return

    val dur = transport.durationMs.coerceAtLeast(1L)
    val frac = (transport.positionMs.toFloat() / dur).coerceIn(0f, 1f)

    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clickable { onExpand() }
    ) {
        Column {
            // razor-thin progress along the top edge
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtImage(transport.album, transport.artist, artVersion, Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        transport.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        transport.artist.ifEmpty { "Unknown artist" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (transport.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (transport.playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- home */

@Composable
fun HomeScreen(
    transportFlow: StateFlow<TransportState>?,
    lastPlayed: LastPlayed?,
    albums: List<LibAlbum>,
    artVersion: Int,
    dacName: String,
    exclusive: Boolean,
    onOutput: () -> Unit,
    favoritesCount: Int,
    onFavorites: () -> Unit,
    onResumeLast: () -> Unit,
    onShuffleAll: () -> Unit,
    onOpenAlbum: (LibAlbum) -> Unit,
    onShortcut: (LibSection) -> Unit
) {
    val transport = rememberTransport(transportFlow)
    val showingNow = transport.title.isNotEmpty()
    val rateLabel = transport.format.substringAfterLast("·").trim()
    val outputLabel =
        if (dacName.isNotEmpty()) {
            if (rateLabel.isNotEmpty()) "$dacName \u2022 $rateLabel" else dacName
        } else "Phone audio"
    val bitPerfect = exclusive && transport.playing && transport.format.isNotEmpty()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Spacer(Modifier.height(26.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("VCHiFiMP", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Direct USB audio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutputPill(outputLabel, bitPerfect, onOutput)
            }
            Spacer(Modifier.height(22.dp))
        }

        // ---- continue listening ----
        val lp = lastPlayed
        if (lp != null) {
            item {
                Text("Continue listening", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Card(
                    Modifier.fillMaxWidth().clickable { onResumeLast() },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtImage(lp.album, lp.artist, artVersion, Modifier.size(64.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                lp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                lp.artist.ifEmpty { "Unknown artist" },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (lp.codec.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    lp.codec,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = onResumeLast) {
                            Text(if (showingNow && transport.paused) "Resume" else "Play")
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }

        // ---- shortcuts ----
        item {
            Text("Library", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile("Songs", Modifier.weight(1f)) { onShortcut(LibSection.SONGS) }
                ShortcutTile("Albums", Modifier.weight(1f)) { onShortcut(LibSection.ALBUMS) }
                ShortcutTile("Artists", Modifier.weight(1f)) { onShortcut(LibSection.ARTISTS) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onShuffleAll, modifier = Modifier.weight(1f)) {
                    Text("Shuffle all")
                }
                OutlinedButton(onClick = onFavorites, modifier = Modifier.weight(1f)) {
                    Text("Favorites ($favoritesCount)")
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // ---- quick picks ----
        if (albums.isNotEmpty()) {
            item {
                Text("Albums", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    items(
                        albums.take(12),
                        key = { it.name + "\u0000" + it.artist },
                        contentType = { "album" }
                    ) { a ->
                        Column(
                            Modifier.width(128.dp).clickable { onOpenAlbum(a) }
                        ) {
                            ArtImage(a.name, a.artist, artVersion,
                                     Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(6.dp))
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                 style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "${plural(albums.sumOf { it.tracks.size }, "track")}  ·  " +
                    plural(albums.size, "album"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ShortcutTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.padding(0.dp)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
             style = MaterialTheme.typography.labelLarge)
    }
}

/** Shared artwork box: keyed on album+artist so it follows the item it belongs to. */
@Composable
fun ArtImage(album: String, artist: String, artVersion: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = album + "\u0000" + artist
    var bitmap by remember(key, artVersion) {
        mutableStateOf(ArtCache.peek(album, artist))
    }
    LaunchedEffect(key, artVersion) {
        if (bitmap == null) bitmap = ArtCache.load(ctx, album, artist)
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (album.isNotEmpty()) {
            Text(
                album.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MusicScreen(
    albums: List<LibAlbum>,
    tracks: List<LibTrack>,
    scanning: Boolean,
    scanState: String,
    artVersion: Int,
    onOpenAlbum: (LibAlbum) -> Unit,
    onOpenArtist: (String) -> Unit,
    onPlayList: (List<LibTrack>, Int) -> Unit,
    section: LibSection,
    onSection: (LibSection) -> Unit,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onFavorites: () -> Unit = {},
    favoritesCount: Int = 0,
    onTrackMenu: (LibTrack) -> Unit = {}
) {
    var grid by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf(SortBy.TITLE) }
    var artistFilter by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val sortedTracks = remember(tracks, sort) {
        when (sort) {
            SortBy.TITLE -> tracks.sortedBy { it.title.lowercase() }
            SortBy.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }))
            SortBy.ALBUM -> tracks.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNo }))
            SortBy.DURATION -> tracks.sortedByDescending { it.durationMs }
            SortBy.MODIFIED -> tracks.sortedByDescending { it.modifiedMs }
        }
    }
    val sortedAlbums = remember(albums, sort) {
        when (sort) {
            SortBy.ARTIST -> albums.sortedBy { it.artist.lowercase() }
            SortBy.DURATION -> albums.sortedByDescending { it.durationMs }
            SortBy.MODIFIED -> albums.sortedByDescending { a ->
                a.tracks.maxOfOrNull { it.modifiedMs } ?: 0L
            }
            else -> albums.sortedBy { it.name.lowercase() }
        }
    }
    val artists = remember(tracks) {
        tracks.groupBy { it.artist.ifEmpty { "Unknown artist" } }
            .map { (name, list) -> Triple(name, list, list.map { it.album }.distinct().size) }
    }
    val sortedArtists = remember(artists, sort) {
        when (sort) {
            SortBy.DURATION -> artists.sortedByDescending { it.second.size }
            SortBy.MODIFIED -> artists.sortedByDescending { a ->
                a.second.maxOfOrNull { it.modifiedMs } ?: 0L
            }
            else -> artists.sortedBy { it.first.lowercase() }
        }
    }

    val q = query.trim().lowercase()
    val shownTracks = remember(sortedTracks, q) {
        if (q.isEmpty()) sortedTracks else sortedTracks.filter {
            it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q)
        }
    }
    val shownAlbums = remember(sortedAlbums, q) {
        if (q.isEmpty()) sortedAlbums else sortedAlbums.filter {
            it.name.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }
    }
    val shownArtists = remember(sortedArtists, q) {
        if (q.isEmpty()) sortedArtists else sortedArtists.filter { it.first.lowercase().contains(q) }
    }

    // artist drill-down
    val af = artistFilter
    if (af != null) {
        ArtistDetail(
            name = af,
            tracks = sortedTracks.filter { it.artist.ifEmpty { "Unknown artist" } == af },
            artVersion = artVersion,
            onBack = { artistFilter = null },
            onPlayList = onPlayList
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.headlineMedium,
                     modifier = Modifier.weight(1f))
                IconButton(onClick = onFavorites) {
                    BadgedBox(badge = {
                        if (favoritesCount > 0) Badge { Text("$favoritesCount") }
                    }) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = "Favorites",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onChooseFolder) { Text("Folder") }
                TextButton(onClick = onRescan, enabled = !scanning) { Text("Rescan") }
            }
            Text(scanState, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search songs, albums, artists") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LibSection.entries.forEachIndexed { i, sec ->
                    SegmentedButton(
                        selected = section == sec,
                        onClick = { onSection(sec) },
                        shape = SegmentedButtonDefaults.itemShape(i, LibSection.entries.size)
                    ) { Text(sec.label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) {
                        Text("Sort: ${sort.label}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        SortBy.entries.forEach { sb ->
                            DropdownMenuItem(
                                text = { Text(sb.label) },
                                onClick = { sort = sb; menu = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (section == LibSection.SONGS || section == LibSection.ALBUMS) {
                    TextButton(onClick = { grid = !grid }) {
                        Text(if (grid) "List view" else "Grid view")
                    }
                }
            }
        }

        when (section) {
            LibSection.SONGS -> if (grid) {
                SongGrid(shownTracks, artVersion, onPlayList, onTrackMenu)
            } else {
                SongList(shownTracks, artVersion, onPlayList, onTrackMenu)
            }
            LibSection.ALBUMS -> if (grid) {
                AlbumGrid(shownAlbums, artVersion, albums.size, onOpenAlbum)
            } else {
                AlbumList(shownAlbums, artVersion, onOpenAlbum)
            }
            LibSection.ARTISTS -> ArtistList(shownArtists) { onOpenArtist(it) }
        }
    }
}

enum class LibSection(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists") }

private enum class SortBy(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), DURATION("Duration"), MODIFIED("Modified")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    tracks: List<LibTrack>,
    artVersion: Int,
    onPlayList: (List<LibTrack>, Int) -> Unit,
    onTrackMenu: ((LibTrack) -> Unit)? = null
) {
    if (tracks.isEmpty()) {
        Text("No tracks. Choose a folder.", Modifier.padding(20.dp),
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        itemsIndexed(
            tracks,
            key = { i, t -> t.uri.toString() + "#" + i },
            contentType = { _, _ -> "song" }
        ) { i, t ->
            val subtitle = remember(t) {
                listOfNotNull(
                    t.artist.ifEmpty { null },
                    fmtTime(t.durationMs),
                    if (t.bitDepth > 0) "${t.bitDepth}-bit" else null
                ).joinToString("  ·  ")
            }
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { onPlayList(tracks, i) },
                        onLongClick = { onTrackMenu?.invoke(t) }
                    )
                    .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackThumb(t, artVersion, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.bodyLarge)
                    Text(
                        subtitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onTrackMenu != null) {
                    IconButton(onClick = { onTrackMenu.invoke(t) }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SongGrid(
    tracks: List<LibTrack>,
    artVersion: Int,
    onPlayList: (List<LibTrack>, Int) -> Unit,
    onTrackMenu: ((LibTrack) -> Unit)? = null
) {
    if (tracks.isEmpty()) {
        Text("No tracks. Choose a folder.", Modifier.padding(20.dp),
             style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(
            count = tracks.size,
            key = { i -> tracks[i].uri.toString() + "#" + i },
            contentType = { "song" }
        ) { i ->
            val t = tracks[i]
            Column(
                Modifier.combinedClickable(
                    onClick = { onPlayList(tracks, i) },
                    onLongClick = { onTrackMenu?.invoke(t) }
                )
            ) {
                TrackArt(t, artVersion)
                Spacer(Modifier.height(6.dp))
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodyMedium)
                Text(t.artist.ifEmpty { "Unknown artist" }, maxLines = 1,
                     overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrackThumb(t: LibTrack, artVersion: Int, size: Int) {
    val ctx = LocalContext.current
    val key = t.album + "\u0000" + t.artist
    var bitmap by remember(key, artVersion) {
        mutableStateOf(ArtCache.peek(t.album, t.artist))
    }
    LaunchedEffect(key, artVersion) {
        if (bitmap == null) bitmap = ArtCache.load(ctx, t.album, t.artist)
    }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(t.title.take(1).uppercase(), style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackArt(t: LibTrack, artVersion: Int) {
    val ctx = LocalContext.current
    val key = t.album + "\u0000" + t.artist
    var bitmap by remember(key, artVersion) {
        mutableStateOf(ArtCache.peek(t.album, t.artist))
    }
    LaunchedEffect(key, artVersion) {
        if (bitmap == null) bitmap = ArtCache.load(ctx, t.album, t.artist)
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(t.title.take(1).uppercase(), style = MaterialTheme.typography.displaySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AlbumGrid(
    albums: List<LibAlbum>,
    artVersion: Int,
    total: Int,
    onOpenAlbum: (LibAlbum) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            albums,
            key = { it.name + "\u0000" + it.artist },
            contentType = { "album" }
        ) { a ->
            AlbumCard(a, artVersion) { onOpenAlbum(a) }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AlbumList(albums: List<LibAlbum>, artVersion: Int, onOpenAlbum: (LibAlbum) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(
            albums,
            key = { it.name + "\u0000" + it.artist },
            contentType = { "album" }
        ) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenAlbum(a) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumThumb(a, artVersion, 52)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.bodyLarge)
                    Text("${a.artist}  ·  ${plural(a.tracks.size, "track")}",
                         maxLines = 1, overflow = TextOverflow.Ellipsis,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AlbumThumb(album: LibAlbum, artVersion: Int, size: Int) {
    val ctx = LocalContext.current
    val key = album.name + "\u0000" + album.artist
    var bitmap by remember(key, artVersion) {
        mutableStateOf(ArtCache.peek(album.name, album.artist))
    }
    LaunchedEffect(key, artVersion) {
        if (bitmap == null) bitmap = ArtCache.load(ctx, album.name, album.artist)
    }
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(album.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<Triple<String, List<LibTrack>, Int>>,
    onPick: (String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        itemsIndexed(
            artists,
            key = { _, a -> a.first },
            contentType = { _, _ -> "artist" }
        ) { _, a ->
            ListItem(
                headlineContent = { Text(a.first, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${plural(a.second.size, "track")}  ·  ${plural(a.third, "album")}")
                },
                modifier = Modifier.clickable { onPick(a.first) }
            )
        }
    }
}

@Composable
private fun ArtistDetail(
    name: String,
    tracks: List<LibTrack>,
    artVersion: Int,
    onBack: () -> Unit,
    onPlayList: (List<LibTrack>, Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Library") }
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.titleMedium)
                Text(plural(tracks.size, "track"), style = MaterialTheme.typography.bodySmall)
            }
        }
        SongList(tracks, artVersion, onPlayList)
    }
}

@Composable
private fun AlbumCard(album: LibAlbum, artVersion: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val key = album.name + "\u0000" + album.artist
    var bitmap by remember(key, artVersion) {
        mutableStateOf(ArtCache.peek(album.name, album.artist))
    }
    LaunchedEffect(key, artVersion) {
        if (bitmap == null) bitmap = ArtCache.load(ctx, album.name, album.artist)
    }
    val bits = album.tracks.firstOrNull()?.bitDepth ?: 0

    Column(Modifier.clickable { onClick() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp, contentDescription = album.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    album.name.take(1).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (bits > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                ) {
                    Text(
                        "$bits-bit",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                tonalElevation = 4.dp
            ) {
                Icon(
                    Icons.Default.PlayArrow, contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(6.dp).size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
             style = MaterialTheme.typography.bodyMedium)
        Text(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* -------------------------------------------------------------- output */

data class Telemetry(
    val urbs: Long, val errors: Long, val underruns: Long, val ringPct: Long,
    val rate: Long, val bits: Long, val alt: Long, val maxpkt: Long, val sync: Long,
    val latencyMs: Long, val minUs: Long, val maxUs: Long, val avgUs: Long,
    val done: Long, val packed: Long, val filled: Long, val inflightMs: Long
) {
    companion object {
        fun from(a: LongArray) = Telemetry(
            a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8],
            a[9], a[10], a[11], a[12], a[13], a[14], a[15], a[16]
        )
    }
}

private fun syncName(s: Long) = when (s) {
    1L -> "async"; 2L -> "adaptive"; 3L -> "sync"; else -> "none"
}

@Composable
fun OutputScreen(
    analyzer: AudioAnalyzer?,
    transportFlow: StateFlow<TransportState>?,
    dacName: String,
    exclusive: Boolean,
    onExclusive: (Boolean) -> Unit,
    volume: Float,
    onVolume: (Float) -> Unit,
    player: UsbDirectPlayer?,
    hardwareVolume: Boolean,
    onHardwareVolume: (Boolean) -> Unit,
    dynamicColors: Boolean = true
) {
    val transport = rememberTransport(transportFlow)

    var tel by remember { mutableStateOf<Telemetry?>(null) }
    var hwVol by remember { mutableStateOf<IntArray?>(null) }
    var summary by remember { mutableStateOf("") }

    // polled only while this screen is composed: no cost during playback elsewhere
    LaunchedEffect(player, transport.playing) {
        while (true) {
            val raw = player?.telemetry() ?: LongArray(0)
            tel = if (raw.size >= 17) Telemetry.from(raw) else null
            hwVol = player?.hwVolumeInfo()
            val s = player?.summary() ?: ""
            if (s.isNotEmpty()) summary = s
            delay(400)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Spacer(Modifier.height(22.dp))
            Text("Output", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))

            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dacName.ifEmpty { "no DAC connected" },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        val perfect = exclusive && tel != null &&
                                transport.format.isNotEmpty()
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (perfect) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                if (perfect) "BIT-PERFECT" else
                                    if (exclusive) "IDLE" else "MIXER",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (transport.title.isEmpty()) "idle"
                        else "${transport.format.ifEmpty { "—" }}  ·  ${transport.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exclusive DAC", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (exclusive) "bit-perfect, this app owns the device"
                        else "Android mixer, other apps can play",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = exclusive, onCheckedChange = onExclusive)
            }

            if (hwVol != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hardware volume", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "DAC feature unit · %.1f dB".format(hwVol!![3] / 256.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = hardwareVolume, onCheckedChange = onHardwareVolume)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("vol", style = MaterialTheme.typography.labelMedium)
                Slider(value = volume, onValueChange = onVolume,
                       modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(18.dp))
            Text("Pipeline", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
        }

        item {
            val t = tel
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (t == null) {
                        Text(
                            "No active stream. Start playback to see live numbers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Metric("Format", "${t.rate / 1000} kHz · ${t.bits}-bit · alt ${t.alt}" +
                                " · ${syncName(t.sync)}")
                        Metric("In flight", "${t.inflightMs} ms")
                        Metric("Buffered", "${t.latencyMs} ms")
                        Metric("Ring buffer", "${t.ringPct}%  (${t.filled} frames)")
                        Metric("URBs reaped", "${t.urbs}   errors ${t.errors}")
                        Metric("Underruns", "${t.underruns}")
                        Metric(
                            "URB interval",
                            "min ${t.minUs / 1000} · avg ${t.avgUs / 1000} · " +
                            "max ${t.maxUs / 1000} ms"
                        )
                        Metric("Endpoint", "maxpkt ${t.maxpkt}")
                    }
                }
            }

            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("Descriptors", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Spectrum", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }

        item {
            val accent = rememberArtAccent(
                transport.album, transport.artist, dynamicColors
            )?.primary
            SpectrumPanel(analyzer, accent = accent)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SettingsScreen(
    folderLabel: String,
    trackCount: Int,
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onClearCache: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    dynamicColors: Boolean,
    onDynamicColors: (Boolean) -> Unit,
    outputBits: Int,
    onOutputBits: (Int) -> Unit,
    srcQuality: Int,
    onSrcQuality: (Int) -> Unit,
    dither: Boolean,
    onDither: (Boolean) -> Unit,
    gapless: Boolean,
    onGapless: (Boolean) -> Unit,
    autoReclaim: Boolean,
    onAutoReclaim: (Boolean) -> Unit,
    sleepRemainingMs: Long?,
    sleepMinutes: Int,
    onSleepMinutes: (Int) -> Unit,
    skipShort: Boolean,
    onSkipShort: (Boolean) -> Unit,
    version: String = "1.0.0"
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(18.dp))

            /* ------------------------------------------------- audio engine */
            SettingsSection("Audio engine") {
                SettingTitle(
                    "Output bit depth",
                    if (outputBits == 16) "16-bit alt setting, no truncation of 24-bit files"
                    else "24-bit default · lossless zero-padding for 16-bit sources"
                )
                OptionChips(
                    options = listOf("24-bit (Default)", "16-bit"),
                    selected = if (outputBits == 16) 1 else 0,
                    onSelect = { onOutputBits(if (it == 0) 24 else 16) }
                )
                Spacer(Modifier.height(14.dp))

                SettingTitle(
                    "Resampler quality",
                    when (srcQuality) {
                        NativeResampler.QUALITY_BEST -> "SRC_SINC_BEST_QUALITY · slowest, most accurate"
                        NativeResampler.QUALITY_FASTEST -> "SRC_SINC_FASTEST · lowest CPU, wider alias floor"
                        else -> "SRC_SINC_MEDIUM_QUALITY · default trade-off"
                    }
                )
                OptionChips(
                    options = listOf("Medium Sinc", "Best Sinc", "Fastest Sinc"),
                    selected = 1 - srcQuality.coerceIn(0, 2),
                    onSelect = {
                        onSrcQuality(
                            when (it) {
                                0 -> NativeResampler.QUALITY_MEDIUM
                                1 -> NativeResampler.QUALITY_BEST
                                else -> NativeResampler.QUALITY_FASTEST
                            }
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))

                SwitchRow(
                    "TPDF dither",
                    "Triangular dither in the native packer, pre-USB",
                    dither, onDither
                )
                HorizontalDivider()
                SwitchRow(
                    "Gapless playback",
                    "Continue into the next track when the format matches",
                    gapless, onGapless
                )
                HorizontalDivider()
                SwitchRow(
                    "Auto-reclaim USB DAC on replug",
                    "Resume bit-perfect output after the DAC returns",
                    autoReclaim, onAutoReclaim
                )
            }

            Spacer(Modifier.height(18.dp))

            /* --------------------------------------------------- sleep timer */
            SettingsSection("Sleep timer") {
                val left = sleepRemainingMs ?: 0L
                val running = left > 0
                SettingTitle(
                    "Pause after",
                    if (running) {
                        val s = (left / 1000).toInt()
                        "Remaining %d:%02d".format(s / 60, s % 60)
                    } else "Off — playback continues"
                )
                OptionChips(
                    options = listOf("Off", "15 min", "30 min", "45 min", "60 min"),
                    selected = when (sleepMinutes) {
                        15 -> 1; 30 -> 2; 45 -> 3; 60 -> 4; else -> 0
                    },
                    onSelect = {
                        onSleepMinutes(
                            when (it) { 1 -> 15; 2 -> 30; 3 -> 45; 4 -> 60; else -> 0 }
                        )
                    }
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ------------------------------------------- library & appearance */
            SettingsSection("Library & appearance") {
                SettingTitle("Theme", "System follows the phone's dark mode")
                OptionChips(
                    options = listOf("System", "Dark", "Light"),
                    selected = when (themeMode) {
                        ThemeMode.SYSTEM -> 0; ThemeMode.DARK -> 1; ThemeMode.LIGHT -> 2
                    },
                    onSelect = {
                        onThemeMode(
                            when (it) {
                                1 -> ThemeMode.DARK
                                2 -> ThemeMode.LIGHT
                                else -> ThemeMode.SYSTEM
                            }
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))

                SwitchRow(
                    "Dynamic album-art colors",
                    "Tint Now Playing and the spectrum from the cover",
                    dynamicColors, onDynamicColors
                )
                HorizontalDivider()
                SwitchRow(
                    "Skip short tracks (< 30s)",
                    "Ignore stingers and interludes when scanning",
                    skipShort, onSkipShort
                )
                HorizontalDivider()

                Spacer(Modifier.height(10.dp))
                Text(
                    folderLabel.ifEmpty { "No folder selected" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    plural(trackCount, "track") + " cached",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseFolder) { Text("Choose folder") }
                    OutlinedButton(onClick = onRescan) { Text("Rescan") }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onClearCache) { Text("Clear artwork cache") }
            }

            Spacer(Modifier.height(18.dp))

            /* --------------------------------------------------------- about */
            SettingsSection("About") {
                Text("VCHiFiMP  v$version", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bit-perfect USB audio · native C engine over raw usbfs · " +
                    "libsamplerate (BSD-2) for rate conversion · 24-bit/16-bit UAC1 output",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Software volume with TPDF dither · no root, Knox untouched",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/* --------------------------------------------------------- settings atoms */

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

@Composable
private fun SettingTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OptionChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
