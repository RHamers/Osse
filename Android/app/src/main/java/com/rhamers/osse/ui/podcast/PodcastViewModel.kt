package com.rhamers.osse.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.repository.PodcastRepository
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.model.QueueItem
import com.rhamers.osse.rss.OpmlParser
import com.rhamers.osse.rss.OpmlWriter
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(val feeds: List<PodcastIndexFeed>) : SearchState()
    data class Error(val message: String) : SearchState()
}

sealed class OpmlResult {
    data class Success(val count: Int) : OpmlResult()
    data class Error(val message: String) : OpmlResult()
}

class PodcastViewModel(private val repo: PodcastRepository) : ViewModel() {

    val subscriptions: StateFlow<List<Podcast>> = repo.observePodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val queue: StateFlow<List<QueueItem>> = repo.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _importResult = MutableStateFlow<OpmlResult?>(null)
    val importResult: StateFlow<OpmlResult?> = _importResult.asStateFlow()

    fun clearImportResult() { _importResult.value = null }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun episodesFor(feedUrl: String): Flow<List<Episode>> = repo.observeEpisodes(feedUrl)

    fun search(query: String) {
        if (query.isBlank()) { _searchState.value = SearchState.Idle; return }
        viewModelScope.launch {
            _searchState.value = SearchState.Loading
            _searchState.value = try {
                SearchState.Results(repo.searchPodcasts(query))
            } catch (e: Exception) {
                SearchState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun clearSearch() { _searchState.value = SearchState.Idle }

    fun subscribe(feed: PodcastIndexFeed) {
        viewModelScope.launch {
            try { repo.subscribe(feed) } catch (_: Exception) {}
        }
    }

    fun subscribeByUrl(feedUrl: String) {
        viewModelScope.launch {
            try { repo.subscribeByUrl(feedUrl) } catch (_: Exception) {}
        }
    }

    fun unsubscribe(feedUrl: String) {
        viewModelScope.launch { repo.unsubscribe(feedUrl) }
    }

    fun refresh(feedUrl: String) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try { repo.refreshEpisodes(feedUrl) } catch (_: Exception) {}
            _isRefreshing.value = false
        }
    }

    fun addToQueue(episodeGuid: String) {
        viewModelScope.launch {
            try { repo.addToQueue(episodeGuid) } catch (_: Exception) {}
        }
    }

    fun removeFromQueue(id: Long) {
        viewModelScope.launch { repo.removeFromQueue(id) }
    }

    fun reorderQueue(items: List<QueueItem>) {
        viewModelScope.launch {
            try { repo.reorderQueue(items) } catch (_: Exception) {}
        }
    }

    fun toggleAutoQueue(feedUrl: String, autoQueue: Boolean) {
        viewModelScope.launch {
            try { repo.toggleAutoQueue(feedUrl, autoQueue) } catch (_: Exception) {}
        }
    }

    fun exportOpml(stream: OutputStream) {
        val opml = OpmlWriter.generate(subscriptions.value)
        stream.writer().use { it.write(opml) }
    }

    fun setDefaultSpeed(feedUrl: String, speed: Float) {
        viewModelScope.launch {
            try { repo.setDefaultSpeed(feedUrl, speed) } catch (_: Exception) {}
        }
    }

    fun importOpml(stream: InputStream) {
        viewModelScope.launch {
            try {
                val urls = OpmlParser.parse(stream)
                repo.importOpml(urls)
                _importResult.value = OpmlResult.Success(urls.size)
            } catch (e: Exception) {
                _importResult.value = OpmlResult.Error(e.message ?: "Import failed")
            }
        }
    }

    class Factory(private val repo: PodcastRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PodcastViewModel(repo) as T
    }
}
