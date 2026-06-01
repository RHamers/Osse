package com.rhamers.osse.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpmlParserTest {

    private fun parse(xml: String): List<String> =
        OpmlParser.parse(xml.trimIndent().byteInputStream())

    private fun opml(bodyContent: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <opml version="1.0">
          <head><title>My Podcasts</title></head>
          <body>$bodyContent</body>
        </opml>
    """

    @Test fun parsesSingleFeed() {
        val urls = parse(opml("""
            <outline text="Podcast One" type="rss" xmlUrl="https://feed1.example.com/rss"/>
        """))
        assertEquals(listOf("https://feed1.example.com/rss"), urls)
    }

    @Test fun parsesMultipleFeeds() {
        val urls = parse(opml("""
            <outline text="Podcast One" type="rss" xmlUrl="https://feed1.example.com/rss"/>
            <outline text="Podcast Two" type="rss" xmlUrl="https://feed2.example.com/rss"/>
        """))
        assertEquals(
            listOf("https://feed1.example.com/rss", "https://feed2.example.com/rss"),
            urls
        )
    }

    @Test fun parsesNestedFeedsInsideCategory() {
        val urls = parse(opml("""
            <outline text="Tech">
              <outline text="Podcast One" type="rss" xmlUrl="https://feed1.example.com/rss"/>
              <outline text="Podcast Two" type="rss" xmlUrl="https://feed2.example.com/rss"/>
            </outline>
        """))
        assertEquals(
            listOf("https://feed1.example.com/rss", "https://feed2.example.com/rss"),
            urls
        )
    }

    @Test fun ignoresCategoryOutlinesWithNoXmlUrl() {
        val urls = parse(opml("""
            <outline text="Tech Category"/>
            <outline text="Podcast One" type="rss" xmlUrl="https://feed1.example.com/rss"/>
        """))
        assertEquals(listOf("https://feed1.example.com/rss"), urls)
    }

    @Test fun returnsEmptyListForEmptyBody() {
        val urls = parse(opml(""))
        assertTrue(urls.isEmpty())
    }

    @Test fun acceptsOutlineWithXmlUrlButNoTypeAttribute() {
        val urls = parse(opml("""
            <outline text="Podcast" xmlUrl="https://feed.example.com/rss"/>
        """))
        assertEquals(listOf("https://feed.example.com/rss"), urls)
    }

    @Test fun deduplicatesDuplicateFeedUrls() {
        val urls = parse(opml("""
            <outline text="Podcast" xmlUrl="https://feed.example.com/rss"/>
            <outline text="Podcast Again" xmlUrl="https://feed.example.com/rss"/>
        """))
        assertEquals(listOf("https://feed.example.com/rss"), urls)
    }
}
