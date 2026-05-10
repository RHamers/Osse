package com.rhamers.osse.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioSourceTest {

    @Test
    fun rssFeedUsesEnclosureUrlDirectly() {
        val url = "https://example.com/episode.mp3"
        val item = AudioSource.RssFeed(url).toMediaItem()
        assertEquals(Uri.parse(url), item.localConfiguration?.uri)
    }

    @Test
    fun youtubeStreamUsesYoutubeUrlDirectly() {
        val url = "https://youtube.com/watch?v=dQw4w9WgXcQ"
        val item = AudioSource.YoutubeStream(url).toMediaItem()
        assertEquals(Uri.parse(url), item.localConfiguration?.uri)
    }

    @Test
    fun youtubeDownloadedConvertsToFileUri() {
        val path = "/data/user/0/com.rhamers.osse/files/audio/track.mp3"
        val item = AudioSource.YoutubeDownloaded(path).toMediaItem()
        assertEquals(Uri.fromFile(File(path)), item.localConfiguration?.uri)
    }

    @Test
    fun localFilePreservesUri() {
        val uri = Uri.parse("content://media/external/audio/media/1234")
        val item = AudioSource.LocalFile(uri).toMediaItem()
        assertEquals(uri, item.localConfiguration?.uri)
    }

    // toDisplayString

    @Test
    fun toDisplayString_localFileReturnsLastPathSegment() {
        val uri = Uri.parse("content://media/external/audio/media/my_song.mp3")
        assertEquals("my_song.mp3", AudioSource.LocalFile(uri).toDisplayString())
    }

    @Test
    fun toDisplayString_rssFeedReturnsEnclosureUrl() {
        val url = "https://example.com/episode.mp3"
        assertEquals(url, AudioSource.RssFeed(url).toDisplayString())
    }

    @Test
    fun toDisplayString_youtubeStreamContainsYoutubePrefix() {
        val url = "https://youtube.com/watch?v=abc123"
        val result = AudioSource.YoutubeStream(url).toDisplayString()
        assertTrue(result.startsWith("YouTube:"))
        assertTrue(result.contains(url))
    }

    @Test
    fun toDisplayString_youtubeDownloadedReturnsFilename() {
        assertEquals(
            "track.mp3",
            AudioSource.YoutubeDownloaded("/data/user/0/com.rhamers.osse/files/audio/track.mp3").toDisplayString()
        )
    }
}
