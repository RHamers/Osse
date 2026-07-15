package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.model.QueueItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePodcastRepository : PodcastRepository {

    val podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    private val episodesByFeed = mutableMapOf<String, MutableStateFlow<List<Episode>>>()
    val queue = MutableStateFlow<List<QueueItem>>(emptyList())

    var searchResult: List<PodcastIndexFeed> = emptyList()
    var searchError: Exception? = null
    var lastSubscribed: PodcastIndexFeed? = null
    var lastSubscribedUrl: String? = null
    var lastUnsubscribed: String? = null
    var lastRefreshed: String? = null
    var refreshAllPodcastsCallCount = 0
    val positionUpdates = mutableListOf<Pair<String, Long>>()
    val queuedGuids = mutableListOf<String>()
    val removedQueueIds = mutableListOf<Long>()
    var reorderedQueue: List<QueueItem>? = null
    var lastAutoQueueToggle: Pair<String, Boolean>? = null

    var subscribeError: Exception? = null
    var subscribeByUrlError: Exception? = null
    var refreshError: Exception? = null
    var addToQueueError: Exception? = null
    var reorderQueueError: Exception? = null
    var toggleAutoQueueError: Exception? = null
    var setDefaultSpeedError: Exception? = null

    fun setEpisodes(feedUrl: String, episodes: List<Episode>) {
        episodesByFeed.getOrPut(feedUrl) { MutableStateFlow(emptyList()) }.value = episodes
    }

    override fun observePodcasts(): Flow<List<Podcast>> = podcasts

    override fun observeEpisodes(feedUrl: String): Flow<List<Episode>> =
        episodesByFeed.getOrPut(feedUrl) { MutableStateFlow(emptyList()) }

    override fun observeQueue(): Flow<List<QueueItem>> = queue

    override suspend fun searchPodcasts(query: String): List<PodcastIndexFeed> {
        searchError?.let { throw it }
        return searchResult
    }

    override suspend fun subscribe(feed: PodcastIndexFeed) {
        subscribeError?.let { throw it }
        lastSubscribed = feed
    }
    override suspend fun subscribeByUrl(feedUrl: String) {
        subscribeByUrlError?.let { throw it }
        lastSubscribedUrl = feedUrl
    }
    override suspend fun refreshEpisodes(feedUrl: String) {
        refreshError?.let { throw it }
        lastRefreshed = feedUrl
    }
    override suspend fun refreshAllPodcasts() { refreshAllPodcastsCallCount++ }
    override suspend fun unsubscribe(feedUrl: String) { lastUnsubscribed = feedUrl }
    override suspend fun updateEpisodePosition(guid: String, positionMs: Long) {
        positionUpdates += guid to positionMs
    }
    override suspend fun addToQueue(episodeGuid: String) {
        addToQueueError?.let { throw it }
        queuedGuids += episodeGuid
    }
    override suspend fun removeFromQueue(id: Long) { removedQueueIds += id }
    override suspend fun reorderQueue(items: List<QueueItem>) {
        reorderQueueError?.let { throw it }
        reorderedQueue = items
    }
    override suspend fun toggleAutoQueue(feedUrl: String, autoQueue: Boolean) {
        toggleAutoQueueError?.let { throw it }
        lastAutoQueueToggle = feedUrl to autoQueue
    }

    val completedGuids = mutableListOf<String>()
    override suspend fun markEpisodeCompleted(guid: String) { completedGuids += guid }

    val importedUrls = mutableListOf<String>()
    var importError: Exception? = null
    override suspend fun importOpml(feedUrls: List<String>) {
        importError?.let { throw it }
        importedUrls += feedUrls
    }

    var lastDefaultSpeed: Pair<String, Float>? = null
    override suspend fun setDefaultSpeed(feedUrl: String, speed: Float) {
        setDefaultSpeedError?.let { throw it }
        lastDefaultSpeed = feedUrl to speed
    }
}
