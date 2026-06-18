package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class YoutubeCacheDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: YoutubeCacheDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.youtubeCacheDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `getAll returns empty flow initially`() = runBlocking {
        assertTrue(dao.getAll().first().isEmpty())
    }

    @Test
    fun `insert and getById returns entity`() = runBlocking {
        dao.insert(entity("vid1"))
        val result = dao.getById("vid1")
        assertNotNull(result)
        assertEquals("Lofi Beats", result!!.title)
    }

    @Test
    fun `getAll returns inserted entity`() = runBlocking {
        dao.insert(entity("vid1"))
        val result = dao.getAll().first()
        assertEquals(1, result.size)
        assertEquals("vid1", result[0].videoId)
    }

    @Test
    fun `insert replaces on conflict`() = runBlocking {
        dao.insert(entity("vid1", title = "Old Title"))
        dao.insert(entity("vid1", title = "New Title"))
        assertEquals(1, dao.getAll().first().size)
        assertEquals("New Title", dao.getById("vid1")!!.title)
    }

    @Test
    fun `delete removes entity`() = runBlocking {
        dao.insert(entity("vid1"))
        dao.delete("vid1")
        assertNull(dao.getById("vid1"))
        assertTrue(dao.getAll().first().isEmpty())
    }

    @Test
    fun `getById returns null when not present`() = runBlocking {
        assertNull(dao.getById("missing"))
    }

    private fun entity(
        videoId: String,
        title: String = "Lofi Beats",
    ) = YoutubeCacheEntity(
        videoId = videoId,
        title = title,
        channel = "Lofi Girl",
        durationSecs = 225,
        thumbnailPath = "/cache/$videoId.jpg",
        localPath = "/cache/$videoId.webm",
        cachedAt = 1_000_000L,
    )
}
