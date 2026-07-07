package com.rhamers.osse.data.repository

import com.rhamers.osse.data.api.PodcastIndexClient
import com.rhamers.osse.data.api.PodcastIndexFeed
import com.rhamers.osse.data.db.EpisodeDao
import com.rhamers.osse.data.db.EpisodeEntity
import com.rhamers.osse.data.db.PodcastDao
import com.rhamers.osse.data.db.PodcastEntity
import com.rhamers.osse.data.db.QueueItemDao
import com.rhamers.osse.data.db.QueueItemEntity
import com.rhamers.osse.data.db.QueueItemWithDetails
import com.rhamers.osse.model.Episode
import com.rhamers.osse.model.Podcast
import com.rhamers.osse.model.QueueItem
import com.rhamers.osse.rss.RssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DefaultPodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queueItemDao: QueueItemDao
) : PodcastRepository {

    private val http = OkHttpClient()

    override fun observePodcasts(): Flow<List<Podcast>> =
        podcastDao.observeAll().map { it.map(PodcastEntity::toDomain) }

    override fun observeEpisodes(feedUrl: String): Flow<List<Episode>> =
        episodeDao.observeForPodcast(feedUrl).map { it.map(EpisodeEntity::toDomain) }

    override fun observeQueue(): Flow<List<QueueItem>> =
        queueItemDao.observeQueue().map { it.map(QueueItemWithDetails::toDomain) }

    override suspend fun searchPodcasts(query: String): List<PodcastIndexFeed> = withContext(Dispatchers.IO) {
        PodcastIndexClient.api.searchPodcasts(query).feeds
    }

    override suspend fun subscribe(feed: PodcastIndexFeed) = withContext(Dispatchers.IO) {
        podcastDao.upsert(
            PodcastEntity(
                feedUrl = feed.feedUrl,
                title = feed.title,
                artworkUrl = feed.imageUrl,
                description = feed.description,
                subscribedAt = System.currentTimeMillis()
            )
        )
        refreshEpisodes(feed.feedUrl)
    }

    override suspend fun subscribeByUrl(feedUrl: String) = withContext(Dispatchers.IO) {
        // Create entity first so subscribedAt is set before fetching episodes;
        // this prevents existing episodes from being auto-queued on first subscribe.
        if (podcastDao.getByUrl(feedUrl) == null) {
            podcastDao.upsert(
                PodcastEntity(
                    feedUrl = feedUrl,
                    title = feedUrl,
                    artworkUrl = null,
                    description = null,
                    subscribedAt = System.currentTimeMillis()
                )
            )
        }
        refreshEpisodes(feedUrl)
    }

    override suspend fun refreshEpisodes(feedUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).build()
        http.newCall(request).execute().use { response ->
            val body = response.body ?: return@withContext
            val parsed = RssParser.parse(feedUrl, body.byteStream())

            val existing = podcastDao.getByUrl(feedUrl)
            podcastDao.upsert(
                PodcastEntity(
                    feedUrl = feedUrl,
                    title = parsed.title.ifEmpty { existing?.title ?: feedUrl },
                    artworkUrl = parsed.artworkUrl ?: existing?.artworkUrl,
                    description = parsed.description ?: existing?.description,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                    autoQueue = existing?.autoQueue ?: true,
                    subscribedAt = existing?.subscribedAt ?: 0L,
                    defaultSpeed = existing?.defaultSpeed ?: 1.0f
                )
            )

            episodeDao.upsertAll(parsed.episodes)

            val podcast = podcastDao.getByUrl(feedUrl) ?: return@withContext
            if (podcast.autoQueue) {
                val existingGuids = queueItemDao.getAllGuids().toSet()
                val toAdd = parsed.episodes.filter {
                    it.pubDateMs > podcast.subscribedAt && it.guid !in existingGuids
                }
                val maxPos = queueItemDao.maxPosition() ?: -1
                toAdd.forEachIndexed { i, ep ->
                    queueItemDao.insert(
                        QueueItemEntity(
                            episodeGuid = ep.guid,
                            position = maxPos + 1 + i,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    override suspend fun refreshAllPodcasts() = withContext(Dispatchers.IO) {
        podcastDao.getAll().forEach { podcast ->
            try {
                refreshEpisodes(podcast.feedUrl)
            } catch (_: Exception) {
                // One feed failing (network error, malformed RSS) shouldn't block the rest.
            }
        }
    }

    override suspend fun unsubscribe(feedUrl: String) {
        podcastDao.getByUrl(feedUrl)?.let { podcastDao.delete(it) }
    }

    override suspend fun updateEpisodePosition(guid: String, positionMs: Long) {
        episodeDao.updatePosition(guid, positionMs)
    }

    override suspend fun addToQueue(episodeGuid: String) = withContext(Dispatchers.IO) {
        if (episodeGuid in queueItemDao.getAllGuids()) return@withContext
        val maxPos = queueItemDao.maxPosition() ?: -1
        queueItemDao.insert(
            QueueItemEntity(
                episodeGuid = episodeGuid,
                position = maxPos + 1,
                addedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun removeFromQueue(id: Long) = withContext(Dispatchers.IO) {
        queueItemDao.deleteById(id)
    }

    override suspend fun reorderQueue(items: List<QueueItem>) = withContext(Dispatchers.IO) {
        items.forEachIndexed { index, item ->
            queueItemDao.updatePosition(item.id, index)
        }
    }

    override suspend fun toggleAutoQueue(feedUrl: String, autoQueue: Boolean) = withContext(Dispatchers.IO) {
        podcastDao.setAutoQueue(feedUrl, autoQueue)
    }

    override suspend fun markEpisodeCompleted(guid: String) = withContext(Dispatchers.IO) {
        episodeDao.setCompleted(guid, true)
    }

    override suspend fun importOpml(feedUrls: List<String>) {
        feedUrls.forEach { subscribeByUrl(it) }
    }

    override suspend fun setDefaultSpeed(feedUrl: String, speed: Float) = withContext(Dispatchers.IO) {
        podcastDao.setDefaultSpeed(feedUrl, speed.coerceIn(1.0f, 2.0f))
    }
}

private fun PodcastEntity.toDomain() =
    Podcast(feedUrl, title, artworkUrl, description, autoQueue, subscribedAt, defaultSpeed)

private fun EpisodeEntity.toDomain() =
    Episode(guid, podcastFeedUrl, title, enclosureUrl, durationMs, showNotes, pubDateMs, positionMs, completed)

private fun QueueItemWithDetails.toDomain() =
    QueueItem(id, position, episodeGuid, episodeTitle, podcastTitle, durationMs, enclosureUrl)
