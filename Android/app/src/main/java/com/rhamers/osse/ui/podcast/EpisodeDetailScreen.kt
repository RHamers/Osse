package com.rhamers.osse.ui.podcast

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rhamers.osse.model.Episode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailScreen(
    episode: Episode,
    onPlay: (Episode) -> Unit,
    onAddToQueue: (Episode) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues()
) {
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        TopAppBar(
            title = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                if (episode.pubDateMs > 0) {
                    Text(
                        formatDate(episode.pubDateMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (episode.durationMs > 0) {
                    Text(
                        formatDuration(episode.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { onAddToQueue(episode) }) {
                    Icon(Icons.Default.AddToQueue, contentDescription = "Add to queue", modifier = Modifier.size(24.dp))
                }
                FilledIconButton(onClick = { onPlay(episode) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
            }
        }

        HorizontalDivider()

        if (episode.showNotes == null) {
            Text(
                "No show notes available.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Show notes" },
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, episode.showNotes, "text/html", "UTF-8", null)
                }
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
