package com.rhamers.osse.ui.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.Episode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    feedUrl: String,
    podcastTitle: String,
    viewModel: PodcastViewModel,
    onPlayEpisode: (Episode) -> Unit,
    onAddToQueue: (Episode) -> Unit,
    onBack: () -> Unit,
    onEpisodeClick: (Episode) -> Unit = {},
    onSetDefaultSpeed: (Float) -> Unit = {},
    onSetAutoQueue: (Boolean) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues()
) {
    val episodes by remember(feedUrl) { viewModel.episodesFor(feedUrl) }.collectAsState(emptyList())
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val podcast = subscriptions.find { it.feedUrl == feedUrl }
    val savedSpeed = podcast?.defaultSpeed ?: 1.0f
    val savedAutoQueue = podcast?.autoQueue ?: true

    var showSettingsDialog by remember { mutableStateOf(false) }
    var dialogSpeed by remember { mutableFloatStateOf(savedSpeed) }
    var dialogAutoQueue by remember { mutableStateOf(savedAutoQueue) }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Podcast Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        // Locale.US, not the device default: the device may use a
                        // comma decimal separator (e.g. en-NL), but "1.5x" must always
                        // render with a period to match this label's own test expectations.
                        "Default speed: ${String.format(Locale.US, "%.1f", dialogSpeed)}x",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = dialogSpeed,
                        onValueChange = { dialogSpeed = (it * 10).roundToInt() / 10f },
                        valueRange = 1f..2f,
                        steps = 9,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto-queue new episodes", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = dialogAutoQueue, onCheckedChange = { dialogAutoQueue = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetDefaultSpeed(dialogSpeed)
                    onSetAutoQueue(dialogAutoQueue)
                    showSettingsDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(podcastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    dialogSpeed = savedSpeed
                    dialogAutoQueue = savedAutoQueue
                    showSettingsDialog = true
                }) {
                    Icon(Icons.Default.Settings, contentDescription = "Playback settings")
                }
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { viewModel.refresh(feedUrl) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }
        )

        if (episodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                if (isRefreshing) CircularProgressIndicator()
                else Text("No episodes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(episodes, key = { it.guid }) { episode ->
                    EpisodeItem(episode, onPlayEpisode, onAddToQueue, onEpisodeClick)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EpisodeItem(
    episode: Episode,
    onPlay: (Episode) -> Unit,
    onQueue: (Episode) -> Unit,
    onClick: (Episode) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (episode.completed) 0.4f else 1f)
    ) {
        ListItem(
            headlineContent = {
                Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (episode.pubDateMs > 0) {
                        Text(
                            formatDate(episode.pubDateMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (episode.durationMs > 0) {
                        Text(
                            formatDuration(episode.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onQueue(episode) }) {
                        Icon(Icons.Default.AddToQueue, contentDescription = "Add to queue", modifier = Modifier.size(20.dp))
                    }
                    FilledIconButton(onClick = { onPlay(episode) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                    }
                }
            },
            modifier = Modifier.clickable { onClick(episode) }
        )
        if (episode.durationMs > 0 && episode.positionMs > 0) {
            LinearProgressIndicator(
                progress = { (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
private fun formatDate(ms: Long): String = dateFmt.format(Date(ms))

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
