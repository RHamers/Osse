package com.rhamers.osse.ui.podcast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.db.OsseDatabase
import com.rhamers.osse.data.repository.PodcastRepository
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
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

class PodcastViewModel(private val repo: PodcastRepository) : ViewModel() {

    val subscriptions: StateFlow<List<Podcast>> = repo.observePodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

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

    class Factory(private val db: OsseDatabase) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PodcastViewModel(PodcastRepository(db.podcastDao(), db.episodeDao())) as T
    }
}
