package com.rhamers.osse.model

data class Episode(
    val guid: String,
    val podcastFeedUrl: String,
    val title: String,
    val enclosureUrl: String,
    val durationMs: Long,
    val showNotes: String?,
    val pubDateMs: Long,
    val positionMs: Long = 0L,
    val completed: Boolean = false
)
