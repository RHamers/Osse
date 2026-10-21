package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
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

// Mirrors playback_state's shape exactly as it stood at version 6 (before the volume/speed
// columns existed), so building this class reproduces exactly what a device upgrading
// through MIGRATION_6_7 starts from.
@Entity(tableName = "playback_state")
data class LegacyPlaybackStateEntityV6(
    @PrimaryKey val trackId: String,
    val sourceType: String,
    val sourceRef: String,
    val title: String?,
    val positionMs: Long,
    val updatedAt: Long,
)

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class,
        YoutubeCacheEntity::class, LegacyPlaybackStateEntityV6::class],
    version = 6,
    exportSchema = false
)
abstract class LegacyOsseDatabaseV6 : RoomDatabase() {
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
            // Opening the real OsseDatabase class means Room must bridge all the way to its
            // current declared version (7), not just to 6 -- MIGRATION_6_7 has to be supplied
            // here too even though this test is only about 5-to-6 behavior.
            .addMigrations(OsseDatabase.MIGRATION_5_6, OsseDatabase.MIGRATION_6_7)
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

    @Test
    fun `migration 6 to 7 preserves existing rows and backfills default volume and speed`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(dbName)

        val v6db = Room.databaseBuilder(ctx, LegacyOsseDatabaseV6::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        runBlocking {
            v6db.podcastDao().upsert(
                PodcastEntity(feedUrl = "https://feed.com/rss", title = "Kept", artworkUrl = null, description = null)
            )
        }
        v6db.openHelper.writableDatabase.execSQL(
            "INSERT INTO playback_state (trackId, sourceType, sourceRef, title, positionMs, updatedAt) " +
                "VALUES ('PODCAST', 'RSS_FEED', 'https://x.mp3', 'Old Title', 5000, 0)"
        )
        v6db.close()

        val v7db = Room.databaseBuilder(ctx, OsseDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .addMigrations(OsseDatabase.MIGRATION_6_7)
            .build()

        val restored = runBlocking { v7db.playbackStateDao().getByTrackId("PODCAST") }
        assertNotNull("pre-existing row must survive the migration", restored)
        assertEquals("Old Title", restored!!.title)
        assertEquals(1.0f, restored.volume, 0.001f)
        assertEquals(1.0f, restored.speed, 0.001f)

        runBlocking {
            v7db.playbackStateDao().upsert(restored.copy(volume = 0.5f, speed = 1.75f))
            val updated = v7db.playbackStateDao().getByTrackId("PODCAST")!!
            assertEquals(0.5f, updated.volume, 0.001f)
            assertEquals(1.75f, updated.speed, 0.001f)
        }
        v7db.close()
        ctx.deleteDatabase(dbName)
    }
}
