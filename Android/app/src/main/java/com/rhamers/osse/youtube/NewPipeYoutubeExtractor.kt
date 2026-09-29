package com.rhamers.osse.youtube

import com.rhamers.osse.ui.tracks.extractYoutubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.IOException

object NewPipeYoutubeExtractor : YoutubeExtractor {

    override suspend fun extractAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
        extractor.fetchPage()
        val stream = selectBestAudioStream(extractor.audioStreams)
            ?: throw IOException("No audio stream available for $videoUrl")
        stream.content
    }

    override suspend fun searchVideos(query: String, maxResults: Int): List<VideoSearchResult> =
        withContext(Dispatchers.IO) {
            val extractor = ServiceList.YouTube.getSearchExtractor(query)
            extractor.fetchPage()
            toVideoSearchResults(extractor.initialPage.items, maxResults)
        }

    // NewPipeExtractor's WEB client — used for both search and stream extraction here —
    // impersonates the YouTube desktop website; a googlevideo URL it obtains must be
    // requested with a matching desktop-browser UA or the CDN rejects it.
    override fun playbackUserAgent(): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/91.0.4472.124 Safari/537.36"
}

internal fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? =
    streams.maxByOrNull { it.averageBitrate }

internal fun toVideoSearchResults(items: List<InfoItem>, maxResults: Int): List<VideoSearchResult> =
    items.filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
        .take(maxResults)
        .map {
            VideoSearchResult(
                id = extractYoutubeVideoId(it.url),
                title = it.name,
                channel = it.uploaderName ?: "",
                durationSecs = it.duration.coerceAtLeast(0),
            )
        }
