package com.rhamers.osse.ui.tracks

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.model.TrackId
import com.rhamers.osse.model.TrackState
import com.rhamers.osse.model.displayName
import com.rhamers.osse.model.toDisplayString
import com.rhamers.osse.player.MultiTrackService
import com.rhamers.osse.player.TrackPlayer
import com.rhamers.osse.player.YoutubeCacheManager
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeVideoMetadata
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun TrackScreen(
    binder: MultiTrackService.LocalBinder?,
    contentPadding: PaddingValues = PaddingValues(),
    cacheManager: YoutubeCacheManager? = null,
    onSelectPodcast: (TrackId) -> Unit = {},
    onSelectLocalFile: (TrackId) -> Unit = {},
    onEpisodeTitleClick: ((TrackId) -> Unit)? = null,
) {
    if (binder == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Loading" })
        }
        return
    }

    val effectiveCacheManager = cacheManager ?: binder.cacheManager

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TrackId.entries.forEach { id ->
                key(id) {
                    val track = remember(id, binder) { binder.getTrack(id)!! }
                    TrackPlayerCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        label = id.displayName,
                        trackPlayer = track,
                        cacheManager = effectiveCacheManager,
                        onSelectPodcast = { onSelectPodcast(id) },
                        onSelectLocalFile = { onSelectLocalFile(id) },
                        onMarkComplete = { binder.markEpisodeComplete(id) },
                        onTitleClick = onEpisodeTitleClick?.let { cb -> { cb(id) } }
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TrackId.entries.forEach { id ->
                key(id) {
                    val track = remember(id, binder) { binder.getTrack(id)!! }
                    TrackPlayerCard(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = id.displayName,
                        trackPlayer = track,
                        cacheManager = effectiveCacheManager,
                        onSelectPodcast = { onSelectPodcast(id) },
                        onSelectLocalFile = { onSelectLocalFile(id) },
                        onMarkComplete = { binder.markEpisodeComplete(id) },
                        onTitleClick = onEpisodeTitleClick?.let { cb -> { cb(id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackPlayerCard(
    modifier: Modifier,
    label: String,
    trackPlayer: TrackPlayer,
    cacheManager: YoutubeCacheManager,
    onSelectPodcast: () -> Unit,
    onSelectLocalFile: () -> Unit,
    onMarkComplete: () -> Unit,
    onTitleClick: (() -> Unit)? = null,
) {
    val state by trackPlayer.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showYoutubeDialog by remember { mutableStateOf(false) }
    var youtubeInput by remember { mutableStateOf("") }
    var youtubeError by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<VideoSearchResult>>(emptyList()) }
    val busy = extracting || downloading || searching
    val isUrl = remember(youtubeInput) { isYoutubeUrl(youtubeInput) }

    fun dismiss() { showYoutubeDialog = false; youtubeInput = ""; youtubeError = null; searchResults = emptyList() }

    fun doPlay(videoId: String, videoUrl: String, metadata: YoutubeVideoMetadata) {
        scope.launch {
            extracting = true
            youtubeError = null
            try {
                val source = cacheManager.resolveSource(videoId, videoUrl, metadata)
                trackPlayer.setSource(source)
                dismiss()
            } catch (e: Exception) {
                youtubeError = e.message ?: "Failed to extract audio URL"
            } finally {
                extracting = false
            }
        }
    }

    fun doDownload(videoId: String, videoUrl: String, metadata: YoutubeVideoMetadata) {
        scope.launch {
            downloading = true
            youtubeError = null
            try {
                val source = cacheManager.downloadAndCache(videoId, videoUrl, metadata)
                trackPlayer.setSource(source)
                dismiss()
            } catch (e: Exception) {
                youtubeError = e.message ?: "Download failed"
            } finally {
                downloading = false
            }
        }
    }

    TrackCard(
        modifier = modifier,
        label = label,
        state = state,
        isExtracting = extracting,
        onPlay = trackPlayer::play,
        onPause = trackPlayer::pause,
        onRewind = trackPlayer::rewind,
        onForward = trackPlayer::forward,
        onVolumeChange = trackPlayer::setVolume,
        onSpeedChange = trackPlayer::setSpeed,
        onSeek = trackPlayer::seekTo,
        onSelectPodcast = onSelectPodcast,
        onSelectLocalFile = onSelectLocalFile,
        onSelectYoutube = { showYoutubeDialog = true },
        onMarkComplete = onMarkComplete,
        onTitleClick = onTitleClick
    )

    if (showYoutubeDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            title = { Text("YouTube") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = youtubeInput,
                        onValueChange = { youtubeInput = it; youtubeError = null; searchResults = emptyList() },
                        label = { Text("Search or paste URL") },
                        singleLine = true,
                        enabled = !busy,
                        trailingIcon = {
                            if (!isUrl) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            searching = true
                                            youtubeError = null
                                            try {
                                                searchResults = cacheManager.searchVideos(youtubeInput.trim())
                                            } catch (e: Exception) {
                                                youtubeError = e.message ?: "Search failed"
                                            } finally {
                                                searching = false
                                            }
                                        }
                                    },
                                    enabled = youtubeInput.isNotBlank() && !busy,
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (youtubeError != null) {
                        Text(
                            youtubeError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    if (searchResults.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            searchResults.forEach { result ->
                                SearchResultRow(
                                    result = result,
                                    enabled = !busy,
                                    onPlay = {
                                        val meta = YoutubeVideoMetadata(
                                            title = result.title,
                                            channel = result.channel,
                                            durationSecs = result.durationSecs,
                                            thumbnailUrl = "https://i.ytimg.com/vi/${result.id}/hqdefault.jpg",
                                        )
                                        doPlay(result.id, "https://www.youtube.com/watch?v=${result.id}", meta)
                                    },
                                    onDownload = {
                                        val meta = YoutubeVideoMetadata(
                                            title = result.title,
                                            channel = result.channel,
                                            durationSecs = result.durationSecs,
                                            thumbnailUrl = "https://i.ytimg.com/vi/${result.id}/hqdefault.jpg",
                                        )
                                        doDownload(result.id, "https://www.youtube.com/watch?v=${result.id}", meta)
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isUrl) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val urlId = remember(youtubeInput) {
                            Regex("[?&]v=([^&]+)").find(youtubeInput)?.groupValues?.get(1)
                                ?: UUID.randomUUID().toString()
                        }
                        val urlMetadata = remember(urlId) {
                            YoutubeVideoMetadata(
                                title = urlId,
                                channel = "",
                                durationSecs = 0,
                                thumbnailUrl = "https://i.ytimg.com/vi/$urlId/hqdefault.jpg",
                            )
                        }
                        TextButton(
                            enabled = !busy,
                            onClick = { doDownload(urlId, youtubeInput.trim(), urlMetadata) },
                        ) { Text("Download") }
                        TextButton(
                            enabled = !busy,
                            onClick = { doPlay(urlId, youtubeInput.trim(), urlMetadata) },
                        ) { Text("Play") }
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = ::dismiss) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun TrackCard(
    modifier: Modifier = Modifier,
    label: String,
    state: TrackState,
    isExtracting: Boolean = false,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onSelectPodcast: () -> Unit = {},
    onSelectLocalFile: () -> Unit = {},
    onSelectYoutube: () -> Unit = {},
    onMarkComplete: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isLoading || isExtracting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = if (isExtracting) "Extracting" else "Buffering" },
                            strokeWidth = 2.dp
                        )
                    }
                    var menuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Select source")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Podcast") },
                                onClick = { menuExpanded = false; onSelectPodcast() }
                            )
                            DropdownMenuItem(
                                text = { Text("Local File") },
                                onClick = { menuExpanded = false; onSelectLocalFile() }
                            )
                            DropdownMenuItem(
                                text = { Text("YouTube") },
                                onClick = { menuExpanded = false; onSelectYoutube() }
                            )
                        }
                    }
                }
            }

            // Source — tappable when an RSS episode is loaded and caller provides a handler
            val titleClickable = onTitleClick != null && state.source is AudioSource.RssFeed
            Text(
                text = state.title ?: state.source?.toDisplayString() ?: "No source loaded",
                style = MaterialTheme.typography.bodySmall,
                color = if (titleClickable) MaterialTheme.colorScheme.primary
                        else if (state.source != null) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (titleClickable) Modifier.clickable { onTitleClick!!() } else Modifier
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

            // Play / Pause / Seek
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onRewind) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10 seconds")
                }
                Spacer(Modifier.width(8.dp))
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
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onForward) {
                    Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds")
                }
                if (onMarkComplete != null && state.source is AudioSource.RssFeed) {
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

@Composable
private fun SearchResultRow(
    result: VideoSearchResult,
    enabled: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(result.channel)
                    if (result.durationSecs > 0) append(" • ${formatDuration(result.durationSecs)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay, enabled = enabled) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${result.title}")
        }
        IconButton(onClick = onDownload, enabled = enabled) {
            Icon(Icons.Default.Download, contentDescription = "Download ${result.title}")
        }
    }
}

private fun isYoutubeUrl(input: String): Boolean =
    input.contains("youtube.com") || input.contains("youtu.be")

private fun formatDuration(secs: Long): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
