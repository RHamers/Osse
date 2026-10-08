package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val trackId: String,
    val sourceType: String,
    val sourceRef: String,
    val title: String?,
    val positionMs: Long,
    val updatedAt: Long,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
)
