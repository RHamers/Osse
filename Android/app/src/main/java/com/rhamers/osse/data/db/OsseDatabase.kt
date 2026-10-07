package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class, YoutubeCacheEntity::class, PlaybackStateEntity::class],
    version = 6,
    exportSchema = false
)
abstract class OsseDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueItemDao(): QueueItemDao
    abstract fun youtubeCacheDao(): YoutubeCacheDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS playback_state (" +
                    "trackId TEXT NOT NULL PRIMARY KEY, " +
                    "sourceType TEXT NOT NULL, " +
                    "sourceRef TEXT NOT NULL, " +
                    "title TEXT, " +
                    "positionMs INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN defaultSpeed REAL NOT NULL DEFAULT 1.0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS youtube_cache (" +
                    "videoId TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, " +
                    "channel TEXT NOT NULL, " +
                    "durationSecs INTEGER NOT NULL, " +
                    "thumbnailPath TEXT NOT NULL, " +
                    "localPath TEXT NOT NULL, " +
                    "cachedAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN autoQueue INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN subscribedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS queue_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "episodeGuid TEXT NOT NULL, " +
                    "position INTEGER NOT NULL, " +
                    "addedAt INTEGER NOT NULL)"
                )
            }
        }

        @Volatile private var instance: OsseDatabase? = null

        // Test-only seam: instrumented tests bind the real MultiTrackService in-process
        // against the real app's context, so without this override their clearAllTables()
        // calls (and the service's own normal read/writes) land on whatever device is
        // connected — including a real personal device — wiping its actual podcast
        // subscriptions and playback state. Call useIsolatedTestDatabase() before anything
        // calls get() for the first time in the process (the service's onCreate() is
        // typically that first call), since the singleton below is cached after creation.
        @Volatile private var testDatabaseName: String? = null

        // Guarded so multiple androidTest classes' @BeforeClass can call this in any order,
        // whether or not they share a single instrumentation process: only the first caller
        // (before the singleton exists) sets the override and resets the file, so a second
        // call never deletes a database Room may already have open.
        fun useIsolatedTestDatabase(context: Context, name: String = "osse_instrumented_test.db") {
            synchronized(this) {
                if (instance == null) {
                    testDatabaseName = name
                    context.applicationContext.deleteDatabase(name)
                }
            }
        }

        // Test-only: clears the cached singleton and override so each test starts clean.
        fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
                testDatabaseName = null
            }
        }

        fun get(context: Context): OsseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OsseDatabase::class.java,
                testDatabaseName ?: "osse.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
