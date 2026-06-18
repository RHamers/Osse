package com.rhamers.osse.player

import com.rhamers.osse.model.AudioSource
import com.rhamers.osse.youtube.VideoSearchResult
import com.rhamers.osse.youtube.YoutubeVideoMetadata

class FakeYoutubeCacheManager(
    private val resolveResult: Result<AudioSource> = Result.success(
        AudioSource.YoutubeStream("https://fake-stream.example.com/audio")
    ),
    private val downloadResult: Result<Unit> = Result.success(Unit),
    private val searchResult: Result<List<VideoSearchResult>> = Result.success(emptyList()),
) : YoutubeCacheManager {

    override suspend fun resolveSource(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource = resolveResult.getOrThrow()

    override suspend fun downloadAndCache(
        videoId: String,
        videoUrl: String,
        metadata: YoutubeVideoMetadata,
    ): AudioSource {
        downloadResult.getOrThrow()
        return AudioSource.YoutubeDownloaded("/fake/$videoId.webm")
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        searchResult.getOrThrow()

    override suspend fun delete(videoId: String) {}
}
