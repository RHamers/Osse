package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_cache")
data class YoutubeCacheEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channel: String,
    val durationSecs: Long,
    val thumbnailPath: String,
    val localPath: String,
    val cachedAt: Long,
)
