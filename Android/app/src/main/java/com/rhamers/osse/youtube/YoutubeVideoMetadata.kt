package com.rhamers.osse.youtube

data class YoutubeVideoMetadata(
    val title: String,
    val channel: String,
    val durationSecs: Long,
    val thumbnailUrl: String,
)
