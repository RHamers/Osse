package com.rhamers.osse.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class QueueItemWithDetails(
    val id: Long,
    val position: Int,
    val episodeGuid: String,
    @ColumnInfo(name = "episodeTitle") val episodeTitle: String,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String,
    val durationMs: Long,
    val enclosureUrl: String
)

@Dao
interface QueueItemDao {
    @Query("""
        SELECT q.id, q.position, q.episodeGuid,
               e.title AS episodeTitle,
               p.title AS podcastTitle,
               e.durationMs,
               e.enclosureUrl
        FROM queue_items q
        JOIN episodes e ON e.guid = q.episodeGuid
        JOIN podcasts p ON p.feedUrl = e.podcastFeedUrl
        ORDER BY q.position ASC
    """)
    fun observeQueue(): Flow<List<QueueItemWithDetails>>

    @Query("SELECT * FROM queue_items ORDER BY position ASC LIMIT 1")
    suspend fun peekFirst(): QueueItemEntity?

    @Query("SELECT episodeGuid FROM queue_items")
    suspend fun getAllGuids(): List<String>

    @Query("SELECT MAX(position) FROM queue_items")
    suspend fun maxPosition(): Int?

    @Insert
    suspend fun insert(item: QueueItemEntity)

    @Delete
    suspend fun delete(item: QueueItemEntity)

    @Query("DELETE FROM queue_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE queue_items SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)

    @Query("DELETE FROM queue_items WHERE episodeGuid = :guid")
    suspend fun deleteByGuid(guid: String)
}
