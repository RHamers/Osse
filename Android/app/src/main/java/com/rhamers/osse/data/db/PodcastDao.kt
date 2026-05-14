package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getByUrl(feedUrl: String): PodcastEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(podcast: PodcastEntity)

    @Delete
    suspend fun delete(podcast: PodcastEntity)

    @Query("UPDATE podcasts SET autoQueue = :autoQueue WHERE feedUrl = :feedUrl")
    suspend fun setAutoQueue(feedUrl: String, autoQueue: Boolean)
}
