package com.rhamers.osse.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ytaudio.Ytaudio

object YoutubeAudio : YoutubeExtractor {

    override suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        Ytaudio.extractAudioURL(videoUrl)
    }

    override suspend fun downloadAudio(videoUrl: String, outputPath: String) = withContext(Dispatchers.IO) {
        Ytaudio.downloadAudio(videoUrl, outputPath)
    }
}
