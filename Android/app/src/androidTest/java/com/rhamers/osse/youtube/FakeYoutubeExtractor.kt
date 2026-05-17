package com.rhamers.osse.youtube

class FakeYoutubeExtractor(
    private val streamResult: Result<String> = Result.success("https://fake-stream.example.com/audio"),
    private val downloadResult: Result<Unit> = Result.success(Unit),
) : YoutubeExtractor {
    override suspend fun extractAudioUrl(videoUrl: String): String = streamResult.getOrThrow()
    override suspend fun downloadAudio(videoUrl: String, outputPath: String) = downloadResult.getOrThrow()
}
