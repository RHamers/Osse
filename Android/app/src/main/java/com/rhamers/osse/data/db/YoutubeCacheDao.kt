package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface YoutubeCacheDao {
    @Query("SELECT * FROM youtube_cache ORDER BY cachedAt DESC")
    fun getAll(): Flow<List<YoutubeCacheEntity>>

    @Query("SELECT * FROM youtube_cache WHERE videoId = :videoId")
    suspend fun getById(videoId: String): YoutubeCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: YoutubeCacheEntity)

    @Query("DELETE FROM youtube_cache WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
