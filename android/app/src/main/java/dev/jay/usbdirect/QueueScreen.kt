package dev.jay.usbdirect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The queue as a sheet that shares the Now Playing screen rather than a
 * separate destination: opening it pushes the player up instead of replacing it.
 */
@Composable
fun QueueSheet(
    tracks: List<Track>,
    current: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.width(38.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Queue", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (tracks.size == 1) "1 track" else "${tracks.size} tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (tracks.isEmpty()) {
                Text(
                    "Nothing queued.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(22.dp)
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(tracks) { i, t ->
                    val active = i == current
                    ListItem(
                        headlineContent = {
                            Text(
                                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = {
                            if (t.artist.isNotEmpty()) {
                                Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingContent = {
                            Text(
                                if (active) "▶" else "${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(26.dp)
                            )
                        },
                        modifier = Modifier.clickable { onPick(i) }
                    )
                }
            }
        }
    }
}
