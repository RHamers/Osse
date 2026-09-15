package com.rhamers.osse.youtube

interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun downloadAudio(videoUrl: String, outputPath: String)
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    // The User-Agent that must be sent when directly requesting a URL returned by
    // extractAudioUrl — see YoutubeAudio.playbackUserAgent() for why.
    fun playbackUserAgent(): String
}
