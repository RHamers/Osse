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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.model.Podcast

@Composable
fun PodcastSearchScreen(
    viewModel: PodcastViewModel,
    onPodcastClick: (Podcast) -> Unit,
    onImportOpml: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues()
) {
    val subscriptions by viewModel.subscriptions.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; if (it.isBlank()) viewModel.clearSearch() },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            placeholder = { Text("Search podcasts or paste feed URL") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                if (query.startsWith("http")) {
                    viewModel.subscribeByUrl(query)
                    query = ""
                } else {
                    viewModel.search(query)
                }
            })
        )
            IconButton(onClick = onImportOpml) {
                Icon(Icons.Default.FolderOpen, contentDescription = "Import OPML")
            }
        }

        when (val state = searchState) {
            SearchState.Idle -> SubscribedList(subscriptions, onPodcastClick, viewModel)
            SearchState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is SearchState.Results -> SearchResultsList(state.feeds, subscriptions, viewModel) {
                query = ""
                viewModel.clearSearch()
            }
            is SearchState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SubscribedList(
    subscriptions: List<Podcast>,
    onPodcastClick: (Podcast) -> Unit,
    viewModel: PodcastViewModel
) {
    if (subscriptions.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                "No subscriptions yet.\nSearch above or paste a feed URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Text(
        "Subscribed",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    LazyColumn {
        items(subscriptions, key = { it.feedUrl }) { podcast ->
            ListItem(
                headlineContent = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    AsyncImage(
                        model = podcast.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                },
                modifier = Modifier.clickable { onPodcastClick(podcast) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SearchResultsList(
    feeds: List<PodcastIndexFeed>,
    subscriptions: List<Podcast>,
    viewModel: PodcastViewModel,
    onSubscribed: () -> Unit
) {
    val subscribedUrls = remember(subscriptions) { subscriptions.map { it.feedUrl }.toSet() }

    if (feeds.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn {
        items(feeds, key = { it.id }) { feed ->
            val isSubscribed = feed.feedUrl in subscribedUrls
            ListItem(
                headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = feed.description?.let { desc ->
                    { Text(desc, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                },
                leadingContent = {
                    AsyncImage(
                        model = feed.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            if (!isSubscribed) {
                                viewModel.subscribe(feed)
                                onSubscribed()
                            }
                        },
                        enabled = !isSubscribed
                    ) {
                        Icon(
                            if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = if (isSubscribed) "Subscribed" else "Subscribe"
                        )
                    }
                }
            )
            HorizontalDivider()
        }
    }
}
