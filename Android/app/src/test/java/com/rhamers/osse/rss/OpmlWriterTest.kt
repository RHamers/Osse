package com.rhamers.osse.rss

import com.rhamers.osse.model.Podcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlWriterTest {

    private fun podcast(feedUrl: String, title: String) =
        Podcast(feedUrl = feedUrl, title = title, artworkUrl = null, description = null)

    @Test fun emptyListProducesValidOpmlWithEmptyBody() {
        val xml = OpmlWriter.generate(emptyList())
        assertTrue(xml.contains("<opml version=\"1.0\">"))
        assertTrue(xml.contains("<body>"))
        assertTrue(xml.contains("</body>"))
    }

    @Test fun singlePodcastProducesOneOutlineElement() {
        val xml = OpmlWriter.generate(listOf(
            podcast("https://feed.example.com/rss", "My Podcast")
        ))
        assertTrue(xml.contains("xmlUrl=\"https://feed.example.com/rss\""))
        assertTrue(xml.contains("text=\"My Podcast\""))
        assertTrue(xml.contains("type=\"rss\""))
    }

    @Test fun multiplePodcastsProduceMultipleOutlines() {
        val xml = OpmlWriter.generate(listOf(
            podcast("https://feed1.example.com/rss", "Podcast One"),
            podcast("https://feed2.example.com/rss", "Podcast Two"),
        ))
        assertTrue(xml.contains("https://feed1.example.com/rss"))
        assertTrue(xml.contains("https://feed2.example.com/rss"))
        assertEquals(2, xml.split("<outline").size - 1)
    }

    @Test fun titleWithSpecialCharsIsXmlEscaped() {
        val xml = OpmlWriter.generate(listOf(
            podcast("https://feed.example.com/rss", "Rocks & Roll <Live>")
        ))
        assertTrue(xml.contains("text=\"Rocks &amp; Roll &lt;Live&gt;\""))
    }

    @Test fun feedUrlWithAmpersandIsXmlEscaped() {
        val xml = OpmlWriter.generate(listOf(
            podcast("https://feed.example.com/rss?a=1&b=2", "Podcast")
        ))
        assertTrue(xml.contains("xmlUrl=\"https://feed.example.com/rss?a=1&amp;b=2\""))
    }
}
