package com.rhamers.osse.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun toDisplayString_youtubeStreamReturnsYoutubeStream() {
        val url = "https://youtube.com/watch?v=abc123"
        assertEquals("YouTube stream", AudioSource.YoutubeStream(url).toDisplayString())
    }

    @Test
    fun toDisplayString_youtubeDownloadedReturnsFilename() {
        assertEquals(
            "track.mp3",
            AudioSource.YoutubeDownloaded("/data/user/0/com.rhamers.osse/files/audio/track.mp3").toDisplayString()
        )
    }

    // toPlaybackRef

    @Test
    fun toPlaybackRef_rssFeedUsesEnclosureUrlAsRef() {
        val source = AudioSource.RssFeed("https://example.com/ep1.mp3")
        assertEquals("RSS_FEED" to "https://example.com/ep1.mp3", source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_localFileUsesUriStringAsRef() {
        val uri = Uri.parse("content://media/external/audio/media/1234")
        val source = AudioSource.LocalFile(uri)
        assertEquals("LOCAL_FILE" to uri.toString(), source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_youtubeDownloadedDerivesVideoIdFromCachedFilename() {
        val source = AudioSource.YoutubeDownloaded(
            "/data/user/0/com.rhamers.osse/files/youtube/dQw4w9WgXcQ.webm"
        )
        assertEquals("YOUTUBE" to "dQw4w9WgXcQ", source.toPlaybackRef())
    }

    @Test
    fun toPlaybackRef_youtubeStreamReturnsNull() {
        val source = AudioSource.YoutubeStream("https://rr.googlevideo.com/expiring-url")
        assertNull(source.toPlaybackRef())
    }
}
