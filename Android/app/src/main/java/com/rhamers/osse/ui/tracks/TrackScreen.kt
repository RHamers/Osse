package com.rhamers.osse.ui.tracks

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import com.rhamers.osse.model.displayName
import com.rhamers.osse.model.toDisplayString
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.player.TrackPlayer
import kotlin.math.roundToInt

@Composable
fun TrackScreen(
    binder: MultiTrackService.LocalBinder?,
    contentPadding: PaddingValues = PaddingValues()
) {
    if (binder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Loading" })
        }
        return
    }

    val activeIds by binder.activeTrackIds.collectAsState()
    val availableToAdd = TrackId.entries.filter { it !in activeIds }
    var showAddDialog by remember { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        if (activeIds.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No tracks",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a track to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    if (availableToAdd.size == 1) binder.addTrack(availableToAdd[0])
                    else showAddDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Track")
                }
            }
        } else {
            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    activeIds.forEach { id ->
                        key(id) {
                            val track = remember(id, binder) { binder.getTrack(id)!! }
                            TrackPlayerCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                scrollable = true,
                                label = id.displayName,
                                trackPlayer = track,
                                onMarkComplete = if (id == TrackId.PODCAST) binder::markCurrentEpisodeComplete else null
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    activeIds.forEach { id ->
                        key(id) {
                            val track = remember(id, binder) { binder.getTrack(id)!! }
                            TrackPlayerCard(
                                modifier = Modifier.fillMaxWidth(),
                                scrollable = false,
                                label = id.displayName,
                                trackPlayer = track,
                                onMarkComplete = if (id == TrackId.PODCAST) binder::markCurrentEpisodeComplete else null
                            )
                        }
                    }
                }
            }

            if (availableToAdd.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        if (availableToAdd.size == 1) binder.addTrack(availableToAdd[0])
                        else showAddDialog = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add track")
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Track") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    availableToAdd.forEach { id ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { binder.addTrack(id); showAddDialog = false }
                        ) {
                            Text(id.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrackPlayerCard(
    modifier: Modifier,
    scrollable: Boolean,
    label: String,
    trackPlayer: TrackPlayer,
    onMarkComplete: (() -> Unit)? = null
) {
    val state by trackPlayer.state.collectAsState()
    TrackCard(
        modifier = modifier,
        scrollable = scrollable,
        label = label,
        state = state,
        onPlay = trackPlayer::play,
        onPause = trackPlayer::pause,
        onVolumeChange = trackPlayer::setVolume,
        onSpeedChange = trackPlayer::setSpeed,
        onSeek = trackPlayer::seekTo,
        onMarkComplete = onMarkComplete
    )
}

@Composable
fun TrackCard(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    label: String,
    state: TrackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onMarkComplete: (() -> Unit)? = null
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Buffering" },
                        strokeWidth = 2.dp
                    )
                }
            }

            // Source
            Text(
                text = state.source?.toDisplayString() ?: "No source loaded",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.source != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Position (only when duration is known)
            if (state.durationMs > 0) {
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                val sliderPosition = if (dragging) dragValue else state.positionMs.toFloat()

                Slider(
                    value = sliderPosition,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { onSeek(dragValue.toLong()); dragging = false },
                    valueRange = 0f..state.durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Play / Pause
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = { if (state.isPlaying) onPause() else onPlay() },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(28.dp)
                    )
                }
                if (onMarkComplete != null && state.source != null) {
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onMarkComplete) {
                        Icon(
                            Icons.Default.TaskAlt,
                            contentDescription = "Mark as done",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Volume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.VolumeDown, contentDescription = null, modifier = Modifier.size(20.dp))
                Slider(
                    value = state.volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    "${(state.volume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(min = 36.dp)
                )
            }

            // Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Speed", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = state.speed.coerceIn(1f, 2f),
                    onValueChange = { onSpeedChange((it * 10).roundToInt() / 10f) },
                    valueRange = 1f..2f,
                    steps = 9,
                    modifier = Modifier.weight(1f).semantics { testTag = "speed_slider" }
                )
                Text(
                    "${"%.1f".format(state.speed)}x",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.widthIn(min = 36.dp)
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
