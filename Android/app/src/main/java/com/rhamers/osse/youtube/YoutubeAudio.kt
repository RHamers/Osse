package com.rhamers.osse.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ytaudio.Ytaudio

object YoutubeAudio {

    /**
     * Returns a direct streamable audio URL for a YouTube video URL.
     * The URL is time-limited by YouTube; pass it to ExoPlayer immediately.
     * Throws on extraction failure (network error, cipher change, etc.).
     */
    suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        Ytaudio.extractAudioURL(videoUrl)
    }

    /**
     * Downloads the raw audio stream from a YouTube video to [outputPath].
     * The file format is webm or m4a — pass through ffmpeg-kit to convert to MP3 if needed.
     * Throws on failure.
     */
    suspend fun downloadAudio(videoUrl: String, outputPath: String) = withContext(Dispatchers.IO) {
        Ytaudio.downloadAudio(videoUrl, outputPath)
    }
}
