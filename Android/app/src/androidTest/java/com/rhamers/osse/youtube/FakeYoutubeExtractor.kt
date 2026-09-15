package com.rhamers.osse.youtube

import java.io.File

class FakeYoutubeExtractor(
    private val streamResult: Result<String> = Result.success("https://fake-stream.example.com/audio"),
    private val downloadResult: Result<Unit> = Result.success(Unit),
    private val searchResult: Result<List<VideoSearchResult>> = Result.success(emptyList()),
) : YoutubeExtractor {
    override suspend fun extractAudioUrl(videoUrl: String): String = streamResult.getOrThrow()
    override suspend fun downloadAudio(videoUrl: String, outputPath: String) {
        downloadResult.getOrThrow()
        File(outputPath).apply { parentFile?.mkdirs(); createNewFile() }
    }
    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        searchResult.getOrThrow()
    override fun playbackUserAgent(): String = "FakeYoutubeExtractor/1.0"
}
