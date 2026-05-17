package com.rhamers.osse.youtube

interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun downloadAudio(videoUrl: String, outputPath: String)
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>
}
