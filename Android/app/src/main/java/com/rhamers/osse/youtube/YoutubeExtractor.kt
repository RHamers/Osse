package com.rhamers.osse.youtube

interface YoutubeExtractor {
    suspend fun extractAudioUrl(videoUrl: String): String
    suspend fun searchVideos(query: String, maxResults: Int = 10): List<VideoSearchResult>

    // The User-Agent that must be sent when directly requesting a URL returned by
    // extractAudioUrl — see NewPipeYoutubeExtractor.playbackUserAgent() for why. The
    // correct value varies per stream URL (it's bound to whichever client fetched it:
    // Android, iOS, or visionOS), so the stream URL itself must be passed in.
    fun playbackUserAgent(streamUrl: String): String
}
