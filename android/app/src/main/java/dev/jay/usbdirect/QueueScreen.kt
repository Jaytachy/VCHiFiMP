package dev.jay.usbdirect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun QueueScreen(
    tracks: List<Track>,
    transportFlow: kotlinx.coroutines.flow.StateFlow<TransportState>?,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    val current = rememberTransport(transportFlow).index
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) {
                Text("⌄", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "Queue  ·  ${tracks.size}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }

        if (tracks.isEmpty()) {
            Text(
                "Nothing queued yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp)
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
                        Text(t.artist.ifEmpty { "Unknown artist" }, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = {
                        Text(
                            if (active) "▶" else "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
                    },
                    modifier = Modifier.clickable { onPick(i) }
                )
            }
        }
    }
}
