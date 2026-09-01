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

        fun get(context: Context): OsseDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                OsseDatabase::class.java,
                "osse.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { instance = it }
        }
    }
}
