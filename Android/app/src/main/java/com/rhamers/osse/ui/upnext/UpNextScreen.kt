package com.rhamers.osse.ui.upnext

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhamers.osse.model.QueueItem
import com.rhamers.osse.ui.podcast.PodcastViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextScreen(
    viewModel: PodcastViewModel,
    onPlayEpisode: (enclosureUrl: String, title: String) -> Unit,
    onMarkComplete: (QueueItem) -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    val queue by viewModel.queue.collectAsState()
    val localQueue = remember { mutableStateListOf<QueueItem>() }

    LaunchedEffect(queue) {
        if (localQueue.map { it.id } != queue.map { it.id }) {
            localQueue.clear()
            localQueue.addAll(queue)
        }
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localQueue.add(to.index, localQueue.removeAt(from.index))
        viewModel.reorderQueue(localQueue.toList())
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(title = { Text("Up Next") })

        if (localQueue.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Nothing queued yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(localQueue, key = { it.id }) { item ->
                    ReorderableItem(reorderState, key = item.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "elevation")
                        // Capture draggableHandle in the ReorderableItemScope before passing to nested lambdas
                        val dragHandleModifier = Modifier.draggableHandle()
                        ListItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation)
                                .background(MaterialTheme.colorScheme.surface),
                            headlineContent = {
                                Text(item.episodeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    "${item.podcastTitle} · ${formatDuration(item.durationMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                IconButton(onClick = {}, modifier = dragHandleModifier) {
                                    Icon(Icons.Default.DragHandle, contentDescription = "Reorder")
                                }
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onPlayEpisode(item.enclosureUrl, item.episodeTitle) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                    }
                                    IconButton(onClick = { onMarkComplete(item) }) {
                                        Icon(Icons.Default.TaskAlt, contentDescription = "Mark as done")
                                    }
                                    IconButton(onClick = { viewModel.removeFromQueue(item.id) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
