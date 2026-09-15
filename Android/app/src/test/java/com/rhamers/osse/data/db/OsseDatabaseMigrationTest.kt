package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

// Mirrors OsseDatabase's entity set exactly as it stood at version 5 (before
// PlaybackStateEntity existed), so building this class against a real on-disk file
// reproduces exactly what a device upgrading through MIGRATION_5_6 starts from.
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class, YoutubeCacheEntity::class],
    version = 5,
    exportSchema = false
)
abstract class LegacyOsseDatabaseV5 : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsseDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private val inline = Executor { it.run() }

    @Test
    fun `migration 5 to 6 preserves existing data and adds a working playback_state table`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)

        val v5db = Room.databaseBuilder(ctx, LegacyOsseDatabaseV5::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        runBlocking {
            v5db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.com/rss", title = "Kept", artworkUrl = null, description = null)
            )
        }
        v5db.close()

        val v6db = Room.databaseBuilder(ctx, OsseDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .addMigrations(OsseDatabase.MIGRATION_5_6)
            .build()

        val podcasts = runBlocking { v6db.podcastDao().getAll() }
        assertEquals(1, podcasts.size)
        assertEquals("Kept", podcasts[0].title)

        runBlocking {
            v6db.playbackStateDao().upsert(
                PlaybackStateEntity(
                    trackId = "PODCAST", sourceType = "RSS_FEED", sourceRef = "https://x.mp3",
                    title = null, positionMs = 0L, updatedAt = 0L,
                )
            )
            assertNotNull(v6db.playbackStateDao().getByTrackId("PODCAST"))
        }
        v6db.close()
        ctx.deleteDatabase(dbName)
    }
}
