package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastFeedUrl = :feedUrl ORDER BY pubDateMs DESC")
    fun observeForPodcast(feedUrl: String): Flow<List<EpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM episodes WHERE guid = :guid")
    suspend fun getByGuid(guid: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE enclosureUrl = :url LIMIT 1")
    suspend fun getByEnclosureUrl(url: String): EpisodeEntity?

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE guid = :guid")
    suspend fun updatePosition(guid: String, positionMs: Long)

    @Query("UPDATE episodes SET completed = :completed WHERE guid = :guid")
    suspend fun setCompleted(guid: String, completed: Boolean)
}
