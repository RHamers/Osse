package com.rhamers.osse.model

data class Podcast(
    val feedUrl: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val autoQueue: Boolean = true,
    val subscribedAt: Long = 0L,
    val defaultSpeed: Float = 1.0f,
)
