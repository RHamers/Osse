package com.rhamers.osse.player

import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeVideoMetadata

interface YoutubeCacheManager {
    /**
     * Returns a cached local source if available; otherwise extracts a stream URL,
     * kicks off a background download, and returns a stream source.
     */
    suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource

    /**
     * Downloads the audio (and thumbnail) synchronously and returns the cached local source.
     * Returns the cached source immediately if already present.
     */
    suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource

    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    suspend fun delete(videoId: String)
}
