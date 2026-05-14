package com.rhamers.osse.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RssParserTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parse(xml: String, feedUrl: String = "https://feed.example.com/rss") =
        RssParser.parse(feedUrl, xml.trimIndent().byteInputStream())

    private fun rss(channelContent: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            $channelContent
          </channel>
        </rss>
    """

    private fun item(
        title: String = "Episode",
        enclosure: String = "https://example.com/ep.mp3",
        guid: String = "",
        duration: String = "",
        description: String = "",
        pubDate: String = ""
    ) = buildString {
        append("<item>")
        append("<title>$title</title>")
        if (guid.isNotEmpty()) append("<guid>$guid</guid>")
        append("""<enclosure url="$enclosure" type="audio/mpeg" length="0"/>""")
        if (duration.isNotEmpty()) append("<itunes:duration>$duration</itunes:duration>")
        if (description.isNotEmpty()) append("<description>$description</description>")
        if (pubDate.isNotEmpty()) append("<pubDate>$pubDate</pubDate>")
        append("</item>")
    }

    // ── feed-level fields ────────────────────────────────────────────────────

    @Test fun `parses feed title`() {
        val result = parse(rss("<title>My Podcast</title>"))
        assertEquals("My Podcast", result.title)
    }

    @Test fun `parses itunes image artwork`() {
        val result = parse(rss("""
            <title>P</title>
            <itunes:image href="https://example.com/art.jpg"/>
        """))
        assertEquals("https://example.com/art.jpg", result.artworkUrl)
    }

    @Test fun `parses channel image url artwork when no itunes image`() {
        val result = parse(rss("""
            <title>P</title>
            <image><url>https://example.com/art2.jpg</url></image>
        """))
        assertEquals("https://example.com/art2.jpg", result.artworkUrl)
    }

    @Test fun `itunes image takes priority over channel image`() {
        val result = parse(rss("""
            <title>P</title>
            <itunes:image href="https://itunes.example.com/art.jpg"/>
            <image><url>https://channel.example.com/art.jpg</url></image>
        """))
        assertEquals("https://itunes.example.com/art.jpg", result.artworkUrl)
    }

    @Test fun `artwork is null when absent`() {
        val result = parse(rss("<title>P</title>"))
        assertNull(result.artworkUrl)
    }

    // ── episode parsing ──────────────────────────────────────────────────────

    @Test fun `parses episode title and enclosure`() {
        val result = parse(rss(item(title = "Ep 1", enclosure = "https://example.com/ep1.mp3")))
        assertEquals(1, result.episodes.size)
        assertEquals("Ep 1", result.episodes[0].title)
        assertEquals("https://example.com/ep1.mp3", result.episodes[0].enclosureUrl)
    }

    @Test fun `uses guid when present`() {
        val result = parse(rss(item(guid = "unique-id-123")))
        assertEquals("unique-id-123", result.episodes[0].guid)
    }

    @Test fun `falls back to enclosure url when guid missing`() {
        val result = parse(rss(item(enclosure = "https://example.com/ep.mp3")))
        assertEquals("https://example.com/ep.mp3", result.episodes[0].guid)
    }

    @Test fun `skips episodes without enclosure`() {
        val xml = rss("""
            <item><title>No audio</title></item>
            ${item(title = "Has audio")}
        """)
        val result = parse(xml)
        assertEquals(1, result.episodes.size)
        assertEquals("Has audio", result.episodes[0].title)
    }

    @Test fun `parses multiple episodes in order`() {
        val xml = rss(item(title = "Ep1") + item(title = "Ep2") + item(title = "Ep3"))
        val result = parse(xml)
        assertEquals(listOf("Ep1", "Ep2", "Ep3"), result.episodes.map { it.title })
    }

    @Test fun `parses show notes from description`() {
        val result = parse(rss(item(description = "Show notes here")))
        assertEquals("Show notes here", result.episodes[0].showNotes)
    }

    @Test fun `parses show notes from CDATA description`() {
        val xml = rss("""
            <item>
              <title>Ep</title>
              <enclosure url="https://ex.com/ep.mp3" type="audio/mpeg" length="0"/>
              <description><![CDATA[<b>Bold</b> show notes]]></description>
            </item>
        """)
        val result = parse(xml)
        assertEquals("<b>Bold</b> show notes", result.episodes[0].showNotes)
    }

    // ── duration parsing ─────────────────────────────────────────────────────

    @Test fun `parses HH_MM_SS duration`() {
        val result = parse(rss(item(duration = "1:23:45")))
        val expected = 1 * 3_600_000L + 23 * 60_000L + 45_000L
        assertEquals(expected, result.episodes[0].durationMs)
    }

    @Test fun `parses MM_SS duration`() {
        val result = parse(rss(item(duration = "45:30")))
        assertEquals(45 * 60_000L + 30_000L, result.episodes[0].durationMs)
    }

    @Test fun `parses plain seconds duration`() {
        val result = parse(rss(item(duration = "3600")))
        assertEquals(3_600_000L, result.episodes[0].durationMs)
    }

    @Test fun `duration is zero when absent`() {
        val result = parse(rss(item()))
        assertEquals(0L, result.episodes[0].durationMs)
    }

    // ── pub date parsing ─────────────────────────────────────────────────────

    @Test fun `parses RFC-822 pub date`() {
        val result = parse(rss(item(pubDate = "Mon, 12 May 2025 10:00:00 +0000")))
        assertTrue("pubDateMs should be > 0", result.episodes[0].pubDateMs > 0)
    }

    @Test fun `pub date is zero for unparseable value`() {
        val result = parse(rss(item(pubDate = "not a date")))
        assertEquals(0L, result.episodes[0].pubDateMs)
    }

    // ── feed url is stamped on episodes ──────────────────────────────────────

    @Test fun `episodes carry the feed url`() {
        val result = parse(rss(item()), feedUrl = "https://my.feed.com/rss")
        assertEquals("https://my.feed.com/rss", result.episodes[0].podcastFeedUrl)
    }
}
