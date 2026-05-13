package com.rhamers.osse.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(
        entity = PodcastEntity::class,
        parentColumns = ["feedUrl"],
        childColumns = ["podcastFeedUrl"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("podcastFeedUrl")]
)
data class EpisodeEntity(
    @PrimaryKey val guid: String,
    val podcastFeedUrl: String,
    val title: String,
    val enclosureUrl: String,
    val durationMs: Long,
    val showNotes: String?,
    val pubDateMs: Long,
    val positionMs: Long = 0L
)
