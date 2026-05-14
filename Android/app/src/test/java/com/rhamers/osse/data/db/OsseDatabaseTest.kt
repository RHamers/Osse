package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsseDatabaseTest {

    private lateinit var db: OsseDatabase
    private lateinit var podcastDao: PodcastDao
    private lateinit var episodeDao: EpisodeDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Inline executors make every query run synchronously on the calling thread,
        // so runBlocking + first() never deadlocks waiting for a background thread.
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        podcastDao = db.podcastDao()
        episodeDao = db.episodeDao()
    }

    @After
    fun tearDown() { db.close() }

    // ── PodcastDao ───────────────────────────────────────────────────────────

    @Test fun `upsert and observe podcast`() = runBlocking {
        val podcast = podcast("https://feed.com/rss", "My Podcast")
        podcastDao.upsert(podcast)
        assertEquals(listOf(podcast), podcastDao.observeAll().first())
    }

    @Test fun `observeAll returns podcasts newest first`() = runBlocking {
        val older = podcast("https://a.com/rss", "Older", addedAt = 1000L)
        val newer = podcast("https://b.com/rss", "Newer", addedAt = 2000L)
        podcastDao.upsert(older)
        podcastDao.upsert(newer)
        val observed = podcastDao.observeAll().first()
        assertEquals("Newer", observed[0].title)
        assertEquals("Older", observed[1].title)
    }

    @Test fun `upsert replaces existing podcast`() = runBlocking {
        val original = podcast("https://feed.com/rss", "Original")
        val updated  = podcast("https://feed.com/rss", "Updated")
        podcastDao.upsert(original)
        podcastDao.upsert(updated)
        assertEquals(1, podcastDao.observeAll().first().size)
        assertEquals("Updated", podcastDao.observeAll().first()[0].title)
    }

    @Test fun `getByUrl returns null when not subscribed`() = runBlocking {
        assertNull(podcastDao.getByUrl("https://missing.com/rss"))
    }

    @Test fun `getByUrl returns entity when subscribed`() = runBlocking {
        val podcast = podcast("https://feed.com/rss", "Found")
        podcastDao.upsert(podcast)
        assertEquals(podcast, podcastDao.getByUrl("https://feed.com/rss"))
    }

    @Test fun `delete removes podcast`() = runBlocking {
        val podcast = podcast("https://feed.com/rss", "Gone")
        podcastDao.upsert(podcast)
        podcastDao.delete(podcast)
        assertTrue(podcastDao.observeAll().first().isEmpty())
    }

    // ── EpisodeDao ───────────────────────────────────────────────────────────

    @Test fun `upsertAll and observe episodes`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        val ep = episode(guid = "ep1", pubDateMs = 1000L)
        episodeDao.upsertAll(listOf(ep))
        assertEquals(listOf(ep), episodeDao.observeForPodcast(FEED_URL).first())
    }

    @Test fun `episodes ordered by pubDateMs descending`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        val older = episode(guid = "old", pubDateMs = 1000L)
        val newer = episode(guid = "new", pubDateMs = 9000L)
        episodeDao.upsertAll(listOf(older, newer))
        val result = episodeDao.observeForPodcast(FEED_URL).first()
        assertEquals("new", result[0].guid)
        assertEquals("old", result[1].guid)
    }

    @Test fun `upsertAll replaces existing episode`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        episodeDao.upsertAll(listOf(episode(guid = "ep1", title = "Original")))
        episodeDao.upsertAll(listOf(episode(guid = "ep1", title = "Updated")))
        val result = episodeDao.observeForPodcast(FEED_URL).first()
        assertEquals(1, result.size)
        assertEquals("Updated", result[0].title)
    }

    @Test fun `updatePosition persists new position`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        episodeDao.upsertAll(listOf(episode(guid = "ep1", positionMs = 0L)))
        episodeDao.updatePosition("ep1", 60_000L)
        assertEquals(60_000L, episodeDao.observeForPodcast(FEED_URL).first()[0].positionMs)
    }

    @Test fun `setCompleted marks episode as completed`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        episodeDao.upsertAll(listOf(episode(guid = "ep1")))
        episodeDao.setCompleted("ep1", true)
        val result = episodeDao.observeForPodcast(FEED_URL).first()
        assertTrue(result[0].completed)
    }

    @Test fun `setCompleted can be set back to false`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        episodeDao.upsertAll(listOf(episode(guid = "ep1")))
        episodeDao.setCompleted("ep1", true)
        episodeDao.setCompleted("ep1", false)
        val result = episodeDao.observeForPodcast(FEED_URL).first()
        assertFalse(result[0].completed)
    }

    @Test fun `getByEnclosureUrl returns matching episode`() = runBlocking {
        podcastDao.upsert(podcast(FEED_URL))
        episodeDao.upsertAll(listOf(episode(guid = "ep1")))
        val url = "https://example.com/ep1.mp3"
        val result = episodeDao.getByEnclosureUrl(url)
        assertNotNull(result)
        assertEquals("ep1", result!!.guid)
    }

    @Test fun `getByEnclosureUrl returns null when no match`() = runBlocking {
        assertNull(episodeDao.getByEnclosureUrl("https://example.com/no-such.mp3"))
    }

    @Test fun `deleting podcast cascades to episodes`() = runBlocking {
        val podcast = podcast(FEED_URL)
        podcastDao.upsert(podcast)
        episodeDao.upsertAll(listOf(episode(guid = "ep1")))
        podcastDao.delete(podcast)
        assertTrue(episodeDao.observeForPodcast(FEED_URL).first().isEmpty())
    }

    @Test fun `episodes from other feeds are not returned`() = runBlocking {
        val feedA = "https://a.com/rss"
        val feedB = "https://b.com/rss"
        podcastDao.upsert(podcast(feedA, "A"))
        podcastDao.upsert(podcast(feedB, "B"))
        episodeDao.upsertAll(listOf(
            episode(guid = "a1", feedUrl = feedA),
            episode(guid = "b1", feedUrl = feedB)
        ))
        val result = episodeDao.observeForPodcast(feedA).first()
        assertEquals(1, result.size)
        assertEquals("a1", result[0].guid)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private companion object {
        const val FEED_URL = "https://feed.com/rss"
    }

    private fun podcast(
        feedUrl: String = FEED_URL,
        title: String = "Podcast",
        addedAt: Long = System.currentTimeMillis()
    ) = PodcastEntity(feedUrl = feedUrl, title = title, artworkUrl = null, description = null, addedAt = addedAt)

    private fun episode(
        guid: String = "guid",
        feedUrl: String = FEED_URL,
        title: String = "Episode",
        pubDateMs: Long = 0L,
        positionMs: Long = 0L
    ) = EpisodeEntity(
        guid = guid,
        podcastFeedUrl = feedUrl,
        title = title,
        enclosureUrl = "https://example.com/$guid.mp3",
        durationMs = 0L,
        showNotes = null,
        pubDateMs = pubDateMs,
        positionMs = positionMs
    )
}
