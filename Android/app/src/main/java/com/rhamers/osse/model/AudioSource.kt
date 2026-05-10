package com.rhamers.osse.model

import android.net.Uri
import androidx.media3.common.MediaItem
import java.io.File

sealed class AudioSource {
    data class LocalFile(val uri: Uri) : AudioSource()
    data class RssFeed(val enclosureUrl: String) : AudioSource()
    data class YoutubeStream(val youtubeUrl: String) : AudioSource()
    data class YoutubeDownloaded(val localPath: String) : AudioSource()
}

fun AudioSource.toDisplayString(): String = when (this) {
    is AudioSource.LocalFile         -> uri.lastPathSegment ?: uri.toString()
    is AudioSource.RssFeed           -> enclosureUrl
    is AudioSource.YoutubeStream     -> "YouTube: $youtubeUrl"
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
