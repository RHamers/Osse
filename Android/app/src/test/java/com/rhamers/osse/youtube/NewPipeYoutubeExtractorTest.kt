package com.rhamers.osse.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

private fun audioStream(id: String, bitrate: Int, trackType: AudioTrackType? = null) =
    AudioStream.Builder()
        .setId(id)
        .setContent("https://example.com/$id.m4a", true)
        .setAverageBitrate(bitrate)
        .apply { trackType?.let { setAudioTrackType(it) } }
        .build()

private fun streamInfoItem(id: String, name: String, uploader: String, durationSecs: Long) =
    StreamInfoItem(0, "https://www.youtube.com/watch?v=$id", name, StreamType.VIDEO_STREAM).apply {
        uploaderName = uploader
        duration = durationSecs
    }

class NewPipeYoutubeExtractorTest {

    @Test
    fun `selectBestAudioStream picks the highest average bitrate`() {
        val streams = listOf(audioStream("140", 128), audioStream("251", 160), audioStream("249", 50))

        val best = selectBestAudioStream(streams)

        assertEquals("251", best?.id)
    }

    @Test
    fun `selectBestAudioStream returns null for an empty list`() {
        assertNull(selectBestAudioStream(emptyList()))
    }

    @Test
    fun `selectBestAudioStream prefers the original audio track over a higher-bitrate dub`() {
        val dubbed = audioStream("251-dub", 160, AudioTrackType.DUBBED)
        val original = audioStream("251-orig", 128, AudioTrackType.ORIGINAL)

        val best = selectBestAudioStream(listOf(dubbed, original))

        assertEquals("251-orig", best?.id)
    }

    @Test
    fun `playbackUserAgent picks the user agent matching the stream's client`() {
        val androidUrl = "https://rr.googlevideo.com/videoplayback?itag=251&c=ANDROID"
        val iosUrl = "https://rr.googlevideo.com/videoplayback?itag=251&c=IOS"
        val visionOsUrl = "https://rr.googlevideo.com/videoplayback?itag=251&c=VISIONOS"

        val androidUa = NewPipeYoutubeExtractor.playbackUserAgent(androidUrl)
        val iosUa = NewPipeYoutubeExtractor.playbackUserAgent(iosUrl)
        val visionOsUa = NewPipeYoutubeExtractor.playbackUserAgent(visionOsUrl)

        assertTrue(androidUa.contains("youtube/"))
        assertTrue(androidUa.contains("Android"))
        assertTrue(iosUa.contains("ios.youtube"))
        assertTrue(visionOsUa.contains("visionos.youtube"))
        // all three must differ from each other
        assertTrue(setOf(androidUa, iosUa, visionOsUa).size == 3)
    }

    @Test
    fun `toVideoSearchResults maps stream items and drops non-stream items`() {
        val channelItem = ChannelInfoItem(0, "https://www.youtube.com/channel/xyz", "Some Channel")
        val items: List<InfoItem> = listOf(
            streamInfoItem("abc123", "Title One", "Channel One", 125),
            channelItem,
            streamInfoItem("def456", "Title Two", "Channel Two", 300),
        )

        val results = toVideoSearchResults(items, maxResults = 10)

        assertEquals(
            listOf(
                VideoSearchResult("abc123", "Title One", "Channel One", 125),
                VideoSearchResult("def456", "Title Two", "Channel Two", 300),
            ),
            results,
        )
    }

    @Test
    fun `toVideoSearchResults respects maxResults`() {
        val items: List<InfoItem> = listOf(
            streamInfoItem("a", "A", "C", 10),
            streamInfoItem("b", "B", "C", 10),
            streamInfoItem("c", "C", "C", 10),
        )

        val results = toVideoSearchResults(items, maxResults = 2)

        assertEquals(2, results.size)
    }
}
