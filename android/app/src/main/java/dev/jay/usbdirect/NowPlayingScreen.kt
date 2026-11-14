package dev.jay.usbdirect

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
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
 * Full-screen player. Background is the album art itself, blurred and scrimmed,
 * so the palette follows the artwork instead of a fixed theme colour.
 */
@Composable
fun NowPlayingScreen(
    transport: TransportState,
    art: File?,
    volume: Float,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit
) {
    val bitmap = remember(art) {
        art?.let { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }
    }

    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val dur = transport.durationMs.coerceAtLeast(1L)

    Box(Modifier.fillMaxSize().background(Color(0xFF07070A))) {

        // ---- backdrop: the artwork, blurred and darkened ----
        if (bitmap != null) {
            Image(
                bitmap = bitmap, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.55f)
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xCC000000),
                    0.45f to Color(0x99000000),
                    1f to Color(0xFF07070A)
                )
            )
        )

        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp)
        ) {
            // ---- header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) {
                    Text("⌄", style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    "Now Playing",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xCCFFFFFF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { /* queue comes later */ }) {
                    Icon(Icons.Default.List, contentDescription = "Queue",
                         tint = Color(0xCCFFFFFF))
                }
            }

            Spacer(Modifier.weight(0.06f))

            // ---- artwork ----
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .aspectRatio(1f)
                        .shadow(24.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF17171C))
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap, contentDescription = transport.album,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.08f))

            // ---- titles ----
            Text(
                transport.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                transport.artist.ifEmpty { "Unknown artist" },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xB3FFFFFF),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))

            // ---- seek ----
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
                    style = MaterialTheme.typography.labelMedium, color = Color(0x99FFFFFF)
                )
                Text(
                    "-" + fmtTime((dur - (if (dragging) dragPos.toLong()
                                        else transport.positionMs)).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium, color = Color(0x99FFFFFF)
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---- quality line ----
            if (transport.format.isNotEmpty()) {
                Text(
                    transport.format,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0x99FFFFFF),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.weight(0.10f))

            // ---- transport ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(onClick = onPrev) {
                    Text("|◀", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(76.dp).clickable { onPlayPause() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (transport.playing) "❚❚" else "▶",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                TextButton(onClick = onNext) {
                    Text("▶|", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("vol", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
                Slider(
                    value = volume,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                )
                Text(
                    "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
