package dev.jay.usbdirect

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Full-screen player. The queue opens *inside* this screen: the player shrinks
 * upward and the sheet takes the lower half, so playback context is never lost.
 */
@Composable
fun NowPlayingScreen(
    transportFlow: kotlinx.coroutines.flow.StateFlow<TransportState>?,
    art: File?,
    volume: Float,
    showQueue: Boolean,
    queueTracks: List<Track>,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleQueue: () -> Unit,
    onPickQueue: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {}
) {
    val transport = rememberTransport(transportFlow)
    val bitmap = remember(art) {
        art?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
    }

    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val dur = transport.durationMs.coerceAtLeast(1L)

    val artFraction by animateFloatAsState(if (showQueue) 0.40f else 0.86f, label = "art")

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        if (bitmap != null) {
            Image(
                bitmap = bitmap, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(56.dp).alpha(0.5f)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.background.copy(alpha = 0.86f),
                    0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    1f to MaterialTheme.colorScheme.background
                )
            )
        )

        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 22.dp)
        ) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) {
                    Text("⌄", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "Now Playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleQueue) {
                    Icon(
                        Icons.Default.List, contentDescription = "Queue",
                        tint = if (showQueue) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // player half
            Column(
                Modifier.weight(if (showQueue) 0.56f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(0.04f))

                Box(
                    Modifier
                        .fillMaxWidth(artFraction)
                        .aspectRatio(1f)
                        .shadow(26.dp, RoundedCornerShape(22.dp))
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap, contentDescription = transport.album,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(Modifier.weight(0.06f))

                Column(Modifier.fillMaxWidth()) {
                    Text(
                        transport.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        transport.artist.ifEmpty { "Unknown artist" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(14.dp))

                Slider(
                    value = (if (dragging) dragPos else transport.positionMs.toFloat())
                        .coerceIn(0f, dur.toFloat()),
                    valueRange = 0f..dur.toFloat(),
                    onValueChange = { dragging = true; dragPos = it },
                    onValueChangeFinished = { dragging = false; onSeek(dragPos.toLong()) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        fmtTime(if (dragging) dragPos.toLong() else transport.positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "-" + fmtTime(
                            (dur - if (dragging) dragPos.toLong() else transport.positionMs)
                                .coerceAtLeast(0)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))
                if (transport.format.isNotEmpty()) {
                    Text(
                        transport.format,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.weight(0.10f))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Rounded.Shuffle, contentDescription = "Shuffle",
                            tint = if (transport.shuffle) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onPrev) {
                        Icon(
                            Icons.Rounded.SkipPrevious, contentDescription = "Previous",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(74.dp).clickable { onPlayPause() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (transport.playing) Icons.Rounded.Pause
                                else Icons.Rounded.PlayArrow,
                                contentDescription = if (transport.playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    IconButton(onClick = onNext) {
                        Icon(
                            Icons.Rounded.SkipNext, contentDescription = "Next",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            if (transport.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne
                            else Icons.Rounded.Repeat,
                            contentDescription = "Repeat",
                            tint = if (transport.repeat == RepeatMode.OFF)
                                       MaterialTheme.colorScheme.onSurfaceVariant
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (!showQueue) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "vol", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = volume,
                        onValueChange = onVolume,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                    )
                    Text(
                        "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (showQueue) {
                QueueSheet(
                    tracks = queueTracks,
                    current = transport.index,
                    onPick = onPickQueue,
                    modifier = Modifier.weight(0.44f)
                )
            }
        }
    }
}
