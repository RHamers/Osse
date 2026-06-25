package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val addedAt: Long = System.currentTimeMillis(),
    val autoQueue: Boolean = true,
    val subscribedAt: Long = 0L,
    val defaultSpeed: Float = 1.0f,
)
