package com.rhamers.osse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueItemEntity::class],
    version = 2,
    exportSchema = false
)
abstract class OsseDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueItemDao(): QueueItemDao

    companion object {
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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
