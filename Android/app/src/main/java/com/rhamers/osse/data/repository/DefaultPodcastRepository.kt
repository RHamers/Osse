package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexClient
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.db.EpisodeDao
import com.rhamers.osse.data.db.EpisodeEntity
import com.rhamers.osse.data.db.PodcastDao
import com.rhamers.osse.data.db.PodcastEntity
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.rss.RssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DefaultPodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao
) : PodcastRepository {

    private val http = OkHttpClient()

    override fun observePodcasts(): Flow<List<Podcast>> =
        podcastDao.observeAll().map { it.map(PodcastEntity::toDomain) }

    override fun observeEpisodes(feedUrl: String): Flow<List<Episode>> =
        episodeDao.observeForPodcast(feedUrl).map { it.map(EpisodeEntity::toDomain) }

    override suspend fun searchPodcasts(query: String): List<PodcastIndexFeed> = withContext(Dispatchers.IO) {
        PodcastIndexClient.api.searchPodcasts(query).feeds
    }

    override suspend fun subscribe(feed: PodcastIndexFeed) = withContext(Dispatchers.IO) {
        podcastDao.upsert(
            PodcastEntity(
                feedUrl = feed.feedUrl,
                title = feed.title,
                artworkUrl = feed.imageUrl,
                description = feed.description
            )
        )
        refreshEpisodes(feed.feedUrl)
    }

    override suspend fun subscribeByUrl(feedUrl: String) = withContext(Dispatchers.IO) {
        refreshEpisodes(feedUrl)
    }

    override suspend fun refreshEpisodes(feedUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).build()
        http.newCall(request).execute().use { response ->
            val body = response.body ?: return@withContext
            val parsed = RssParser.parse(feedUrl, body.byteStream())
            if (podcastDao.getByUrl(feedUrl) == null) {
                podcastDao.upsert(
                    PodcastEntity(
                        feedUrl = feedUrl,
                        title = parsed.title,
                        artworkUrl = parsed.artworkUrl,
                        description = parsed.description
                    )
                )
            }
            episodeDao.upsertAll(parsed.episodes)
        }
    }

    override suspend fun unsubscribe(feedUrl: String) {
        podcastDao.getByUrl(feedUrl)?.let { podcastDao.delete(it) }
    }

    override suspend fun updateEpisodePosition(guid: String, positionMs: Long) {
        episodeDao.updatePosition(guid, positionMs)
    }
}

private fun PodcastEntity.toDomain() = Podcast(feedUrl, title, artworkUrl, description)

private fun EpisodeEntity.toDomain() =
    Episode(guid, podcastFeedUrl, title, enclosureUrl, durationMs, showNotes, pubDateMs, positionMs)
