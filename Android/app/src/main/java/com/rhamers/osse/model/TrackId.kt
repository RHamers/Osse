package com.rhamers.osse.model

enum class TrackId {
    PODCAST,
    MUSIC
}

val TrackId.displayName: String get() = when (this) {
    TrackId.PODCAST -> "Podcast"
    TrackId.MUSIC -> "Music"
}
