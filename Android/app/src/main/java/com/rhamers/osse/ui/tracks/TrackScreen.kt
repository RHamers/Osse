package com.rhamers.osse.ui.tracks

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import com.rhamers.osse.model.toDisplayString
import com.rhamers.osse.player.MultiTrackService
import kotlin.math.abs
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

    val podcastTrack = remember(binder) { binder.getTrack(TrackId.PODCAST)!! }
    val musicTrack = remember(binder) { binder.getTrack(TrackId.MUSIC)!! }
    val podcastState by podcastTrack.state.collectAsState()
    val musicState by musicTrack.state.collectAsState()

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TrackCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                scrollable = true,
                label = "Podcast",
                state = podcastState,
                onPlay = podcastTrack::play,
                onPause = podcastTrack::pause,
                onVolumeChange = podcastTrack::setVolume,
                onSpeedChange = podcastTrack::setSpeed,
                onSeek = podcastTrack::seekTo
            )
            TrackCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                scrollable = true,
                label = "Music",
                state = musicState,
                onPlay = musicTrack::play,
                onPause = musicTrack::pause,
                onVolumeChange = musicTrack::setVolume,
                onSpeedChange = musicTrack::setSpeed,
                onSeek = musicTrack::seekTo
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TrackCard(
                modifier = Modifier.fillMaxWidth(),
                scrollable = false,
                label = "Podcast",
                state = podcastState,
                onPlay = podcastTrack::play,
                onPause = podcastTrack::pause,
                onVolumeChange = podcastTrack::setVolume,
                onSpeedChange = podcastTrack::setSpeed,
                onSeek = podcastTrack::seekTo
            )
            TrackCard(
                modifier = Modifier.fillMaxWidth(),
                scrollable = false,
                label = "Music",
                state = musicState,
                onPlay = musicTrack::play,
                onPause = musicTrack::pause,
                onVolumeChange = musicTrack::setVolume,
                onSpeedChange = musicTrack::setSpeed,
                onSeek = musicTrack::seekTo
            )
        }
    }
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
    onSeek: (Long) -> Unit
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
                        modifier = Modifier.size(20.dp).semantics { contentDescription = "Buffering" },
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
            FilledIconButton(
                onClick = { if (state.isPlaying) onPause() else onPlay() },
                modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp)
                )
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
            Text("Speed", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                    FilterChip(
                        selected = abs(state.speed - speed) < 0.01f,
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") }
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
