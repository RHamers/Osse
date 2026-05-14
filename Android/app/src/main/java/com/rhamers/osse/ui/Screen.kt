package com.rhamers.osse.ui

sealed class Screen {
    object Tracks : Screen()
    object PodcastSearch : Screen()
    object UpNext : Screen()
    data class EpisodeList(val feedUrl: String, val podcastTitle: String) : Screen()
}
