package com.rhamers.osse.data.api

import com.rhamers.osse.BuildConfig

object PodcastIndexConfig {
    val API_KEY: String get() = BuildConfig.PODCAST_INDEX_API_KEY
    val API_SECRET: String get() = BuildConfig.PODCAST_INDEX_API_SECRET
    const val BASE_URL = "https://api.podcastindex.org/api/1.0/"
}
