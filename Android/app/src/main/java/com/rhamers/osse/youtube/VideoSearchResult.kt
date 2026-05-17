package com.rhamers.osse.youtube

data class VideoSearchResult(
    val id: String,
    val title: String,
    val channel: String,
    val durationSecs: Long,
)
