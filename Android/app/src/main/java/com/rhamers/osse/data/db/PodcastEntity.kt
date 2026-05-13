package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val addedAt: Long = System.currentTimeMillis()
)
