package com.rhamers.osse.model

data class QueueItem(
    val id: Long,
    val position: Int,
    val episodeGuid: String,
    val episodeTitle: String,
    val podcastTitle: String,
    val durationMs: Long,
    val enclosureUrl: String
)
