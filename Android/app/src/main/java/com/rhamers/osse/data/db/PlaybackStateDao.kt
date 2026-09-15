package com.rhamers.osse.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE trackId = :trackId")
    suspend fun getByTrackId(trackId: String): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackStateEntity)

    @Query("DELETE FROM playback_state WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}
