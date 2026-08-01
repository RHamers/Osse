package com.rhamers.osse.ui.tracks

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoutubeVideoIdTest {

    @Test
    fun extractsIdFromStandardWatchUrl() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun extractsIdFromWatchUrlWithTrailingParams() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"),
        )
    }

    @Test
    fun extractsIdFromWatchUrlWithLeadingParams() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun extractsIdFromShortYoutuBeLink() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun extractsIdFromShortYoutuBeLinkWithTrackingParam() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=abc123trackingtoken"),
        )
    }

    @Test
    fun extractsIdFromShortsLink() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYoutubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun fallsBackToRandomIdForUnparseableUrl() {
        val id = extractYoutubeVideoId("https://www.youtube.com/")
        assertTrue(id.isNotBlank())
        assertTrue(id.matches(Regex("[0-9a-f-]{36}")))
    }
}
