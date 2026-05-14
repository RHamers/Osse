package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class QueueItemDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var podcastDao: PodcastDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var queueItemDao: QueueItemDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        podcastDao = db.podcastDao()
        episodeDao = db.episodeDao()
        queueItemDao = db.queueItemDao()
    }

    @After
    fun tearDown() { db.close() }

    // ── maxPosition ──────────────────────────────────────────────────────────

    @Test fun `maxPosition returns null when queue is empty`() = runBlocking {
        assertNull(queueItemDao.maxPosition())
    }

    @Test fun `maxPosition returns the highest position value`() = runBlocking {
        seed("ep1", "ep2", "ep3")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 5))
        queueItemDao.insert(item("ep3", 2))
        assertEquals(5, queueItemDao.maxPosition())
    }

    // ── getAllGuids ──────────────────────────────────────────────────────────

    @Test fun `getAllGuids returns empty when queue is empty`() = runBlocking {
        assertTrue(queueItemDao.getAllGuids().isEmpty())
    }

    @Test fun `getAllGuids returns every queued episode guid`() = runBlocking {
        seed("ep1", "ep2")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 1))
        assertEquals(setOf("ep1", "ep2"), queueItemDao.getAllGuids().toSet())
    }

    // ── peekFirst ────────────────────────────────────────────────────────────

    @Test fun `peekFirst returns null when queue is empty`() = runBlocking {
        assertNull(queueItemDao.peekFirst())
    }

    @Test fun `peekFirst returns item with the lowest position`() = runBlocking {
        seed("ep1", "ep2", "ep3")
        queueItemDao.insert(item("ep1", 2))
        queueItemDao.insert(item("ep2", 0))
        queueItemDao.insert(item("ep3", 1))
        assertEquals("ep2", queueItemDao.peekFirst()!!.episodeGuid)
    }

    // ── insert / delete ──────────────────────────────────────────────────────

    @Test fun `delete removes the item`() = runBlocking {
        seed("ep1")
        queueItemDao.insert(item("ep1", 0))
        val inserted = queueItemDao.peekFirst()!!
        queueItemDao.delete(inserted)
        assertNull(queueItemDao.peekFirst())
    }

    @Test fun `deleteById removes the item by its generated id`() = runBlocking {
        seed("ep1")
        queueItemDao.insert(item("ep1", 0))
        val id = queueItemDao.peekFirst()!!.id
        queueItemDao.deleteById(id)
        assertNull(queueItemDao.peekFirst())
    }

    @Test fun `deleteByGuid removes the item for that episode`() = runBlocking {
        seed("ep1", "ep2")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 1))
        queueItemDao.deleteByGuid("ep1")
        assertEquals(listOf("ep2"), queueItemDao.getAllGuids())
    }

    @Test fun `deleteByGuid is a no-op when guid is not in queue`() = runBlocking {
        seed("ep1")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.deleteByGuid("ep-not-present")
        assertEquals(listOf("ep1"), queueItemDao.getAllGuids())
    }

    @Test fun `deleteById leaves other items untouched`() = runBlocking {
        seed("ep1", "ep2")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 1))
        val firstId = queueItemDao.peekFirst()!!.id
        queueItemDao.deleteById(firstId)
        assertEquals("ep2", queueItemDao.peekFirst()!!.episodeGuid)
    }

    // ── updatePosition ───────────────────────────────────────────────────────

    @Test fun `updatePosition changes the stored position`() = runBlocking {
        seed("ep1", "ep2")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 1))
        val ep1Id = queueItemDao.peekFirst()!!.id
        queueItemDao.updatePosition(ep1Id, 99)
        // ep2 (position 1) is now first; ep1 (position 99) is last
        assertEquals("ep2", queueItemDao.peekFirst()!!.episodeGuid)
    }

    // ── observeQueue join ────────────────────────────────────────────────────

    @Test fun `observeQueue is empty when nothing is queued`() = runBlocking {
        assertTrue(queueItemDao.observeQueue().first().isEmpty())
    }

    @Test fun `observeQueue returns joined episode and podcast data`() = runBlocking {
        podcastDao.upsert(PodcastEntity(FEED_URL, "My Podcast", null, null))
        episodeDao.upsertAll(listOf(
            EpisodeEntity("ep1", FEED_URL, "My Episode", "https://ex.com/ep1.mp3", 1_800_000L, null, 0L)
        ))
        queueItemDao.insert(item("ep1", 0))

        val result = queueItemDao.observeQueue().first()
        assertEquals(1, result.size)
        with(result[0]) {
            assertEquals("ep1", episodeGuid)
            assertEquals("My Episode", episodeTitle)
            assertEquals("My Podcast", podcastTitle)
            assertEquals(1_800_000L, durationMs)
            assertEquals("https://ex.com/ep1.mp3", enclosureUrl)
        }
    }

    @Test fun `observeQueue returns items ordered by position ascending`() = runBlocking {
        seed("ep1", "ep2", "ep3")
        queueItemDao.insert(item("ep1", 2))
        queueItemDao.insert(item("ep2", 0))
        queueItemDao.insert(item("ep3", 1))

        val guids = queueItemDao.observeQueue().first().map { it.episodeGuid }
        assertEquals(listOf("ep2", "ep3", "ep1"), guids)
    }

    @Test fun `observeQueue emits updated list after delete`() = runBlocking {
        seed("ep1", "ep2")
        queueItemDao.insert(item("ep1", 0))
        queueItemDao.insert(item("ep2", 1))

        val firstId = queueItemDao.peekFirst()!!.id
        queueItemDao.deleteById(firstId)

        val result = queueItemDao.observeQueue().first()
        assertEquals(1, result.size)
        assertEquals("ep2", result[0].episodeGuid)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private companion object {
        const val FEED_URL = "https://feed.com/rss"
    }

    private suspend fun seed(vararg guids: String) {
        podcastDao.upsert(PodcastEntity(FEED_URL, "Podcast", null, null))
        episodeDao.upsertAll(guids.map { guid ->
            EpisodeEntity(guid, FEED_URL, "Episode $guid", "https://ex.com/$guid.mp3", 0L, null, 0L)
        })
    }

    private fun item(guid: String, position: Int) =
        QueueItemEntity(episodeGuid = guid, position = position, addedAt = 0L)
}
