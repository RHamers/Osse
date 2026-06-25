package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PodcastDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: PodcastDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.podcastDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `defaultSpeed is 1_0 when not explicitly set`() = runBlocking {
        dao.upsert(podcast("https://feed.com/rss"))
        val result = dao.getByUrl("https://feed.com/rss")
        assertNotNull(result)
        assertEquals(1.0f, result!!.defaultSpeed, 0.001f)
    }

    @Test
    fun `setDefaultSpeed updates the row`() = runBlocking {
        dao.upsert(podcast("https://feed.com/rss"))
        dao.setDefaultSpeed("https://feed.com/rss", 1.5f)
        val result = dao.getByUrl("https://feed.com/rss")
        assertEquals(1.5f, result!!.defaultSpeed, 0.001f)
    }

    @Test
    fun `setDefaultSpeed does not affect other rows`() = runBlocking {
        dao.upsert(podcast("https://feed1.com/rss"))
        dao.upsert(podcast("https://feed2.com/rss"))
        dao.setDefaultSpeed("https://feed1.com/rss", 2.0f)
        assertEquals(1.0f, dao.getByUrl("https://feed2.com/rss")!!.defaultSpeed, 0.001f)
    }

    private fun podcast(feedUrl: String) = PodcastEntity(
        feedUrl = feedUrl,
        title = "Test Podcast",
        artworkUrl = null,
        description = null,
    )
}
