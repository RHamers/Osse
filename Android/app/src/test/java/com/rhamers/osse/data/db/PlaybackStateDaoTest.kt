package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackStateDaoTest {

    private lateinit var db: OsseDatabase
    private lateinit var dao: PlaybackStateDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val inline = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ctx, OsseDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        dao = db.playbackStateDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test fun `getByTrackId returns null when no row exists`() = runBlocking {
        assertNull(dao.getByTrackId("PODCAST"))
    }

    @Test fun `upsert then getByTrackId returns the row`() = runBlocking {
        val row = row("PODCAST", sourceRef = "https://example.com/ep.mp3")
        dao.upsert(row)
        assertEquals(row, dao.getByTrackId("PODCAST"))
    }

    @Test fun `upsert replaces the existing row for the same trackId`() = runBlocking {
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep1.mp3", positionMs = 1_000L))
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep2.mp3", positionMs = 2_000L))
        val result = dao.getByTrackId("PODCAST")
        assertEquals("https://example.com/ep2.mp3", result!!.sourceRef)
        assertEquals(2_000L, result.positionMs)
    }

    @Test fun `delete removes the row`() = runBlocking {
        dao.upsert(row("MUSIC"))
        dao.delete("MUSIC")
        assertNull(dao.getByTrackId("MUSIC"))
    }

    @Test fun `rows for different trackIds do not collide`() = runBlocking {
        dao.upsert(row("PODCAST", sourceRef = "https://example.com/ep.mp3"))
        dao.upsert(row("MUSIC", sourceRef = "content://media/1"))
        assertEquals("https://example.com/ep.mp3", dao.getByTrackId("PODCAST")!!.sourceRef)
        assertEquals("content://media/1", dao.getByTrackId("MUSIC")!!.sourceRef)
    }

    private fun row(
        trackId: String,
        sourceType: String = "RSS_FEED",
        sourceRef: String = "https://example.com/ep.mp3",
        title: String? = "Episode",
        positionMs: Long = 0L,
    ) = PlaybackStateEntity(
        trackId = trackId,
        sourceType = sourceType,
        sourceRef = sourceRef,
        title = title,
        positionMs = positionMs,
        updatedAt = 0L,
    )
}
