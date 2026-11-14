package dev.jay.usbdirect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
    onExpand: () -> Unit = {},
    swipeUpToExpand: Boolean = false
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
            .then(
                if (!swipeUpToExpand) Modifier else Modifier.pointerInput(Unit) {
                    var drag = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, delta ->
                            if (delta < 0f || drag < 0f) {
                                drag += delta
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (drag < -60.dp.toPx()) onExpand()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    )
                }
            )
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

private fun isHiResTrack(t: LibTrack): Boolean =
    t.bitDepth > 16 || t.sampleRate > 48_000

private fun khzLabel(rate: Int): String {
    val k = rate / 1000.0
    return if (k == k.toInt().toDouble()) "${k.toInt()} kHz" else "%.1f kHz".format(k)
}

/** Corner badge for a hi-res album: depth wins over rate when both are known. */
private fun hiResBadge(a: LibAlbum): String? {
    val bits = a.tracks.maxOfOrNull { it.bitDepth } ?: 0
    val rate = a.tracks.maxOfOrNull { it.sampleRate } ?: 0
    return when {
        bits > 16 -> "$bits-BIT"
        rate > 48_000 -> khzLabel(rate)
        else -> null
    }
}

@Composable
fun HomeScreen(
    transportFlow: StateFlow<TransportState>?,
    lastPlayed: LastPlayed?,
    albums: List<LibAlbum>,
    tracks: List<LibTrack>,
    artVersion: Int,
    dacName: String,
    exclusive: Boolean,
    onOutput: () -> Unit,
    favoritesCount: Int,
    onFavorites: () -> Unit,
    onResumeLast: () -> Unit,
    onPlayPause: () -> Unit,
    onShuffleAll: () -> Unit,
    onPlayTracks: (List<LibTrack>) -> Unit,
    onOpenAlbum: (LibAlbum) -> Unit
) {
    val transport = rememberTransport(transportFlow)
    val showingNow = transport.title.isNotEmpty()
    val rateLabel = transport.format.substringAfterLast("·").trim()
    val outputLabel =
        if (dacName.isNotEmpty()) {
            if (rateLabel.isNotEmpty()) "$dacName \u2022 $rateLabel" else dacName
        } else "Phone audio"
    val bitPerfect = exclusive && transport.playing && transport.format.isNotEmpty()

    val recentlyAdded = remember(albums) {
        albums.sortedByDescending { a -> a.tracks.maxOfOrNull { it.modifiedMs } ?: 0L }
            .take(12)
    }
    val hiResTracks = remember(tracks) { tracks.filter(::isHiResTrack) }
    val hiResAlbums = remember(albums) {
        albums.filter { a -> a.tracks.any(::isHiResTrack) }.take(12)
    }
    val recentTracks = remember(tracks) {
        tracks.sortedByDescending { it.modifiedMs }.take(60)
    }
    val quickPicks = remember(albums) { albums.shuffled().take(12) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
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
                Spacer(Modifier.height(18.dp))
            }
        }

        // ---- 2x2 quick access ----
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeQuickTile(
                        title = "Favorites",
                        subtitle = plural(favoritesCount, "track"),
                        icon = Icons.Default.Favorite,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        onClick = onFavorites
                    )
                    HomeQuickTile(
                        title = "Shuffle All",
                        subtitle = plural(tracks.size, "track"),
                        icon = Icons.Default.Shuffle,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f),
                        onClick = onShuffleAll
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeQuickTile(
                        title = "Hi-Res Masters",
                        subtitle = if (hiResTracks.isEmpty()) "None found"
                                   else plural(hiResTracks.size, "track"),
                        icon = Icons.Default.HighQuality,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (hiResTracks.isNotEmpty()) onPlayTracks(hiResTracks.shuffled())
                        }
                    )
                    HomeQuickTile(
                        title = "Recently Added",
                        subtitle = if (recentTracks.isEmpty()) "No music yet"
                                   else plural(recentTracks.size, "track"),
                        icon = Icons.Default.NewReleases,
                        tint = Color(0xFFF2A65A),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (recentTracks.isNotEmpty()) onPlayTracks(recentTracks)
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ---- continue listening ----
        val lp = lastPlayed
        if (showingNow || lp != null) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    HomeSectionTitle("Continue listening", "Pick up where you left off")
                    Spacer(Modifier.height(10.dp))
                    HomeHeroCard(
                        title = if (showingNow) transport.title else lp?.title.orEmpty(),
                        artist = if (showingNow) transport.artist else lp?.artist.orEmpty(),
                        album = if (showingNow) transport.album else lp?.album.orEmpty(),
                        positionMs = if (showingNow && transport.durationMs > 0)
                            transport.positionMs else lp?.positionMs ?: 0L,
                        durationMs = if (showingNow && transport.durationMs > 0)
                            transport.durationMs else lp?.durationMs ?: 0L,
                        playing = showingNow && transport.playing,
                        paused = showingNow && transport.paused,
                        artVersion = artVersion,
                        onAction = { if (showingNow) onPlayPause() else onResumeLast() }
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // ---- recently added ----
        if (recentlyAdded.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    HomeSectionTitle("Recently added", "Fresh in your library")
                    Spacer(Modifier.height(10.dp))
                    HomeAlbumCarousel(
                        albums = recentlyAdded,
                        artVersion = artVersion,
                        badgeFor = { null },
                        onOpenAlbum = onOpenAlbum
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // ---- hi-res masters ----
        if (hiResAlbums.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    HomeSectionTitle("Hi-Res Masters", "Above 16-bit or 48 kHz")
                    Spacer(Modifier.height(10.dp))
                    HomeAlbumCarousel(
                        albums = hiResAlbums,
                        artVersion = artVersion,
                        badgeFor = ::hiResBadge,
                        onOpenAlbum = onOpenAlbum
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // ---- forgotten gems ----
        if (quickPicks.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    HomeSectionTitle("Forgotten gems", "Quick picks from your collection")
                    Spacer(Modifier.height(10.dp))
                    HomeAlbumCarousel(
                        albums = quickPicks,
                        artVersion = artVersion,
                        badgeFor = { null },
                        onOpenAlbum = onOpenAlbum
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "${plural(tracks.size, "track")}  ·  " +
                        plural(albums.size, "album"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        } else if (albums.isEmpty()) {
            item {
                Text(
                    "No music yet. Choose a folder in Settings to build your library.",
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/* -------------------------------------------------- home components */

@Composable
private fun HomeSectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeQuickTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HomeHeroCard(
    title: String,
    artist: String,
    album: String,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    paused: Boolean,
    artVersion: Int,
    onAction: () -> Unit
) {
    val accent = rememberArtAccent(album, artist, enabled = true)
    val base = accent?.primary ?: MaterialTheme.colorScheme.primary
    val second = accent?.secondary ?: MaterialTheme.colorScheme.secondary
    val dur = durationMs.coerceAtLeast(1L)
    val pos = positionMs.coerceIn(0L, dur)
    val frac = if (durationMs > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val status = when {
        playing -> "Now playing"
        paused -> "Paused"
        else -> "Resume"
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onAction() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101017))
    ) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            // softened cover backdrop, then an art-tinted scrim for legibility
            if (album.isNotEmpty() || artist.isNotEmpty()) {
                ArtImage(
                    album, artist, artVersion,
                    Modifier.fillMaxSize().blur(34.dp).alpha(0.55f)
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(
                            base.copy(alpha = 0.78f),
                            second.copy(alpha = 0.42f),
                            Color(0xFF0B0B10).copy(alpha = 0.94f)
                        )
                    )
                )
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                    )
                )
            )
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(album = album, artist = artist, artVersion = artVersion,
                             modifier = Modifier.size(84.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "CONTINUE LISTENING",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        Text(
                            listOf(artist, album).filter { it.isNotEmpty() }
                                .joinToString("  ·  "),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.76f)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.20f))
                        ) {
                            Box(
                                Modifier.fillMaxWidth(frac).fillMaxHeight()
                                    .clip(CircleShape).background(Color.White)
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "${fmtTime(positionMs)} / ${fmtTime(durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.66f)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    FilledIconButton(
                        onClick = onAction,
                        modifier = Modifier.size(50.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF101017)
                        )
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Resume",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAlbumCarousel(
    albums: List<LibAlbum>,
    artVersion: Int,
    badgeFor: (LibAlbum) -> String?,
    onOpenAlbum: (LibAlbum) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        items(
            albums,
            key = { it.name + "\u0000" + it.artist },
            contentType = { "album" }
        ) { a ->
            Column(Modifier.width(136.dp).clickable { onOpenAlbum(a) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    ArtImage(a.name, a.artist, artVersion, Modifier.fillMaxSize())
                    val badge = badgeFor(a)
                    if (badge != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.64f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        ) {
                            Text(
                                badge,
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    a.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    a.artist.ifEmpty { "Unknown artist" },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    dynamicColors: Boolean = true,
    mixerRouteMissing: Boolean = false
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

            if (mixerRouteMissing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "DAC attached but not registered with Android audio yet — " +
                    "mixer output may go to the phone speaker until it is.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (hwVol != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

            // Keep the slider in its own band so the switch above can never be
            // hit by a slider drag, and vice versa.
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("vol", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = volume, onValueChange = onVolume,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(40.dp)
                )
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
    dynamicMaterial: Boolean,
    onDynamicMaterial: (Boolean) -> Unit,
    amoled: Boolean,
    onAmoled: (Boolean) -> Unit,
    dynamicColors: Boolean,
    onDynamicColors: (Boolean) -> Unit,
    excludedFolders: Set<String>,
    folderOptions: List<String>,
    onToggleFolder: (String) -> Unit,
    autoPlayDac: Boolean,
    onAutoPlayDac: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    smartAutoplay: Boolean,
    onSmartAutoplay: (Boolean) -> Unit,
    swipeUpMini: Boolean,
    onSwipeUpMini: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupMessage: String?,
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
    version: String = "1.0.2"
) {
    var showFolders by remember { mutableStateOf(false) }

    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            confirmButton = {
                TextButton(onClick = { showFolders = false }) { Text("Done") }
            },
            title = { Text("Excluded folders") },
            text = {
                if (folderOptions.isEmpty()) {
                    Text("No folders scanned yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(folderOptions) { f ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onToggleFolder(f) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = excludedFolders.contains(f),
                                    onCheckedChange = { onToggleFolder(f) }
                                )
                                Text(
                                    f,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(18.dp))

            /* ------------------------------------------------ audio & usb dac */
            SettingsSection("Audio & USB DAC") {
                SettingTitle(
                    "Output bit depth",
                    when (outputBits) {
                        16 -> "16-bit alt 1 for every track"
                        24 -> "24-bit alt 2 for every track"
                        else -> "Auto: 16-bit files use alt 1, 24-bit files use alt 2"
                    }
                )
                OptionChips(
                    options = listOf("Auto (Match Source)", "24-bit", "16-bit"),
                    selected = when (outputBits) { 24 -> 1; 16 -> 2; else -> 0 },
                    onSelect = { onOutputBits(when (it) { 1 -> 24; 2 -> 16; else -> 0 }) }
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

                SwitchRow("TPDF dither", "Triangular dither in the native packer", dither, onDither)
                HorizontalDivider()
                SwitchRow("Gapless playback", "Continue into the next track when the format matches", gapless, onGapless)
                HorizontalDivider()
                SwitchRow("Auto-reclaim USB DAC on replug", "Resume bit-perfect output after the DAC returns", autoReclaim, onAutoReclaim)
                HorizontalDivider()
                SwitchRow("Skip short tracks (< 30s)", "Ignore stingers and interludes when scanning", skipShort, onSkipShort)
            }

            Spacer(Modifier.height(18.dp))

            /* ------------------------------------------------------- appearance */
            SettingsSection("Appearance") {
                SettingTitle("Theme", "System follows the phone's dark mode")
                OptionChips(
                    options = listOf("System", "Dark", "Light"),
                    selected = when (themeMode) {
                        ThemeMode.SYSTEM -> 0; ThemeMode.DARK -> 1; ThemeMode.LIGHT -> 2
                    },
                    onSelect = {
                        onThemeMode(when (it) { 1 -> ThemeMode.DARK; 2 -> ThemeMode.LIGHT; else -> ThemeMode.SYSTEM })
                    }
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow("Material You dynamic color", "Use the wallpaper palette instead of the VCHiFiMP scheme", dynamicMaterial, onDynamicMaterial)
                HorizontalDivider()
                SwitchRow("AMOLED pitch black", "Pure #000000 containers while the dark theme is active", amoled, onAmoled)
                HorizontalDivider()
                SwitchRow("Dynamic album-art colors", "Tint Now Playing and the spectrum from the cover", dynamicColors, onDynamicColors)
            }

            Spacer(Modifier.height(18.dp))

            /* ---------------------------------------------------- local library */
            SettingsSection("Local library") {
                SettingTitle("Included music", folderLabel.ifEmpty { "No folder selected" })
                Text(
                    plural(trackCount, "track") + " in the current filter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onChooseFolder) { Text("Choose folder") }
                    Button(onClick = onRescan) { Text("Rescan") }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showFolders = true }) {
                    Text(
                        if (excludedFolders.isEmpty()) "Excluded folders"
                        else "Excluded folders (${excludedFolders.size})"
                    )
                }
                TextButton(onClick = onClearCache) { Text("Clear artwork cache") }
            }

            Spacer(Modifier.height(18.dp))

            /* ------------------------------------------------ playback & gestures */
            SettingsSection("Playback & gestures") {
                SwitchRow("Auto-play on DAC connect", "Resume the paused track when the DAC comes back", autoPlayDac, onAutoPlayDac)
                HorizontalDivider()
                SwitchRow("Smart autoplay", "Append a shuffled batch when the queue ends (Repeat off)", smartAutoplay, onSmartAutoplay)
                HorizontalDivider()
                SwitchRow("Keep screen on while playing", "Only while audio is actually running", keepScreenOn, onKeepScreenOn)
                HorizontalDivider()
                SwitchRow("Swipe up on mini player", "Expand Now Playing with a swipe, like swipe-down to close", swipeUpMini, onSwipeUpMini)
                HorizontalDivider()

                val left = sleepRemainingMs ?: 0L
                SettingTitle(
                    "Sleep timer",
                    if (left > 0) {
                        val sec = (left / 1000).toInt()
                        "Remaining %d:%02d".format(sec / 60, sec % 60)
                    } else "Off — playback continues"
                )
                OptionChips(
                    options = listOf("Off", "15 min", "30 min", "45 min", "60 min"),
                    selected = when (sleepMinutes) { 15 -> 1; 30 -> 2; 45 -> 3; 60 -> 4; else -> 0 },
                    onSelect = {
                        onSleepMinutes(
                            when (it) { 1 -> 15; 2 -> 30; 3 -> 45; 4 -> 60; else -> 0 }
                        )
                    }
                )
            }

            Spacer(Modifier.height(18.dp))

            /* ---------------------------------------------------- backup/restore */
            SettingsSection("Backup & restore") {
                SettingTitle(
                    "vchifimp_backup.json",
                    "Preferences, excluded folders, favourites and the last track"
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExportBackup) { Text("Export JSON") }
                    OutlinedButton(onClick = onImportBackup) { Text("Import JSON") }
                }
                if (!backupMessage.isNullOrEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        backupMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            SettingsSection("About") {
                Text("VCHiFiMP  v$version", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bit-perfect USB audio · native C engine over raw usbfs · " +
                    "libsamplerate (BSD-2) · 24-bit/16-bit UAC1 output",
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
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
