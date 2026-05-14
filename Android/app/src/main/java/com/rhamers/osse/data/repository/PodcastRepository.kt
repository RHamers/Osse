package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import kotlinx.coroutines.flow.Flow

interface PodcastRepository {
    fun observePodcasts(): Flow<List<Podcast>>
    fun observeEpisodes(feedUrl: String): Flow<List<Episode>>
    suspend fun searchPodcasts(query: String): List<PodcastIndexFeed>
    suspend fun subscribe(feed: PodcastIndexFeed)
    suspend fun subscribeByUrl(feedUrl: String)
    suspend fun refreshEpisodes(feedUrl: String)
    suspend fun unsubscribe(feedUrl: String)
    suspend fun updateEpisodePosition(guid: String, positionMs: Long)
}
