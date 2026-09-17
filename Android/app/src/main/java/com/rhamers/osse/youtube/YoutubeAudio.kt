package com.rhamers.osse.youtube

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ytaudio.Ytaudio

private val gson = Gson()

object YoutubeAudio : YoutubeExtractor {

    override suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        Ytaudio.extractAudioURL(videoUrl)
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> = withContext(Dispatchers.IO) {
        val json = Ytaudio.searchVideosJSON(query, maxResults.toLong())
        gson.fromJson(json, Array<VideoSearchResult>::class.java).toList()
    }

    override fun playbackUserAgent(): String = Ytaudio.playbackUserAgent()
}
