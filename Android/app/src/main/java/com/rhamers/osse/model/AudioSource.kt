package com.rhamers.osse.model

import android.net.Uri
import androidx.media3.common.MediaItem
import java.io.File

sealed class AudioSource {
    data class LocalFile(val uri: Uri) : AudioSource()
    data class RssFeed(val enclosureUrl: String) : AudioSource()
    data class YoutubeStream(val youtubeUrl: String, val videoId: String = "") : AudioSource()
    data class YoutubeDownloaded(val localPath: String) : AudioSource()
}

fun AudioSource.toDisplayString(): String = when (this) {
    is AudioSource.LocalFile         -> uri.lastPathSegment ?: uri.toString()
    is AudioSource.RssFeed           -> enclosureUrl
    is AudioSource.YoutubeStream     -> "YouTube stream"
    is AudioSource.YoutubeDownloaded -> localPath.substringAfterLast('/')
}

fun AudioSource.toMediaItem(): MediaItem {
    val uri = when (this) {
        is AudioSource.LocalFile         -> uri.toString()
        is AudioSource.RssFeed           -> enclosureUrl
        is AudioSource.YoutubeStream     -> youtubeUrl
        is AudioSource.YoutubeDownloaded -> Uri.fromFile(File(localPath)).toString()
    }
    return MediaItem.fromUri(uri)
}

object PlaybackSourceType {
    const val RSS_FEED = "RSS_FEED"
    const val LOCAL_FILE = "LOCAL_FILE"
    const val YOUTUBE = "YOUTUBE"
}

// Maps a source to the (sourceType, sourceRef) pair persisted in `playback_state` for
// process-death resume. YoutubeStream persists its durable videoId, not its ephemeral CDN
// URL (which would already be dead by the time anything restores it) -- restorePlaybackState()
// re-resolves a fresh stream (or finds the finished download) through YoutubeCacheManager
// using that videoId, the same way it looks up an enclosure URL for an RssFeed.
fun AudioSource.toPlaybackRef(): Pair<String, String>? = when (this) {
    is AudioSource.RssFeed           -> PlaybackSourceType.RSS_FEED to enclosureUrl
    is AudioSource.LocalFile         -> PlaybackSourceType.LOCAL_FILE to uri.toString()
    is AudioSource.YoutubeDownloaded -> PlaybackSourceType.YOUTUBE to File(localPath).nameWithoutExtension
    is AudioSource.YoutubeStream     -> PlaybackSourceType.YOUTUBE to videoId
}
