package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue_items")
data class QueueItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeGuid: String,
    val position: Int,
    val addedAt: Long
)
