package com.rhamers.osse.youtube

import com.rhamers.osse.ui.tracks.extractYoutubeVideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
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

    // The CDN URL returned by extractAudioUrl() is bound to the client that requested it
    // (Android, required; iOS/visionOS, optional overlays — see YoutubeStreamExtractor's
    // real fetch logic) and must be requested with a matching User-Agent, or the CDN
    // rejects it. There is no "WEB client" URL in this app's actual extraction path.
    override fun playbackUserAgent(streamUrl: String): String {
        val localization = NewPipe.getPreferredLocalization()
        return when {
            YoutubeParsingHelper.isIosStreamingUrl(streamUrl) -> YoutubeParsingHelper.getIosUserAgent(localization)
            YoutubeParsingHelper.isVisionOsStreamingUrl(streamUrl) -> YoutubeParsingHelper.getVisionOsUserAgent(localization)
            else -> YoutubeParsingHelper.getAndroidUserAgent(localization)
        }
    }
}

internal fun selectBestAudioStream(streams: List<AudioStream>): AudioStream? {
    val originalTracks = streams.filter {
        it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
    }
    return originalTracks.ifEmpty { streams }.maxByOrNull { it.averageBitrate }
}

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
