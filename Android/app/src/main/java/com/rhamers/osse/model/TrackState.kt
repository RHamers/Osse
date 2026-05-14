package com.rhamers.osse.model

data class TrackState(
    val source: AudioSource? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLoading: Boolean = false
)
