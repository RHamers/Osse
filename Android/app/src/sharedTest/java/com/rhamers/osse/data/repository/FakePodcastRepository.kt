package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePodcastRepository : PodcastRepository {

    val podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    private val episodesByFeed = mutableMapOf<String, MutableStateFlow<List<Episode>>>()

    var searchResult: List<PodcastIndexFeed> = emptyList()
    var searchError: Exception? = null
    var lastSubscribed: PodcastIndexFeed? = null
    var lastSubscribedUrl: String? = null
    var lastUnsubscribed: String? = null
    var lastRefreshed: String? = null
    val positionUpdates = mutableListOf<Pair<String, Long>>()

    fun setEpisodes(feedUrl: String, episodes: List<Episode>) {
        episodesByFeed.getOrPut(feedUrl) { MutableStateFlow(emptyList()) }.value = episodes
    }

    override fun observePodcasts(): Flow<List<Podcast>> = podcasts

    override fun observeEpisodes(feedUrl: String): Flow<List<Episode>> =
        episodesByFeed.getOrPut(feedUrl) { MutableStateFlow(emptyList()) }

    override suspend fun searchPodcasts(query: String): List<PodcastIndexFeed> {
        searchError?.let { throw it }
        return searchResult
    }

    override suspend fun subscribe(feed: PodcastIndexFeed) { lastSubscribed = feed }
    override suspend fun subscribeByUrl(feedUrl: String) { lastSubscribedUrl = feedUrl }
    override suspend fun refreshEpisodes(feedUrl: String) { lastRefreshed = feedUrl }
    override suspend fun unsubscribe(feedUrl: String) { lastUnsubscribed = feedUrl }
    override suspend fun updateEpisodePosition(guid: String, positionMs: Long) {
        positionUpdates += guid to positionMs
    }
}
