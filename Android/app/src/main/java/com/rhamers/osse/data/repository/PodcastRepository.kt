package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.model.QueueItem
import kotlinx.coroutines.flow.Flow

interface PodcastRepository {
    fun observePodcasts(): Flow<List<Podcast>>
    fun observeEpisodes(feedUrl: String): Flow<List<Episode>>
    fun observeQueue(): Flow<List<QueueItem>>
    suspend fun searchPodcasts(query: String): List<PodcastIndexFeed>
    suspend fun subscribe(feed: PodcastIndexFeed)
    suspend fun subscribeByUrl(feedUrl: String)
    suspend fun refreshEpisodes(feedUrl: String)
    suspend fun unsubscribe(feedUrl: String)
    suspend fun updateEpisodePosition(guid: String, positionMs: Long)
    suspend fun addToQueue(episodeGuid: String)
    suspend fun removeFromQueue(id: Long)
    suspend fun reorderQueue(items: List<QueueItem>)
    suspend fun toggleAutoQueue(feedUrl: String, autoQueue: Boolean)
    suspend fun markEpisodeCompleted(guid: String)
    suspend fun importOpml(feedUrls: List<String>)
}
