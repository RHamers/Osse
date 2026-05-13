package com.rhamers.osse.rss

import android.util.Xml
import com.rhamers.osse.data.db.EpisodeEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val episodes: List<EpisodeEntity>
)

object RssParser {

    private val dateFormats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)
    )

    fun parse(feedUrl: String, input: InputStream): ParsedFeed {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }

        var feedTitle = ""
        var feedArtwork: String? = null
        var feedDescription: String? = null
        val episodes = mutableListOf<EpisodeEntity>()

        var inItem = false
        var inChannelImage = false

        var itemTitle = ""
        var itemGuid = ""
        var itemEnclosureUrl = ""
        var itemDurationMs = 0L
        var itemDescription: String? = null
        var itemPubDateMs = 0L

        fun resetItem() {
            itemTitle = ""; itemGuid = ""; itemEnclosureUrl = ""
            itemDurationMs = 0L; itemDescription = null; itemPubDateMs = 0L
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val ns = parser.namespace ?: ""
            val tag = if (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) parser.name else ""
            val isItunes = ns.contains("itunes")

            when (event) {
                XmlPullParser.START_TAG -> when {
                    tag == "item" -> inItem = true

                    inItem -> when {
                        tag == "title" -> itemTitle = parser.safeNextText()
                        tag == "guid" -> itemGuid = parser.safeNextText()
                        tag == "enclosure" -> itemEnclosureUrl = parser.getAttributeValue(null, "url") ?: ""
                        tag == "duration" && isItunes -> itemDurationMs = parseDuration(parser.safeNextText())
                        tag == "description" && !isItunes -> itemDescription = parser.safeNextText()
                        // <content:encoded> — some feeds put full HTML show notes here
                        tag == "encoded" -> itemDescription = parser.safeNextText()
                        tag == "pubDate" -> itemPubDateMs = parseDate(parser.safeNextText())
                    }

                    // Channel-level artwork — prefer <itunes:image href="..."/> (self-closing)
                    tag == "image" && isItunes -> feedArtwork = parser.getAttributeValue(null, "href")
                    tag == "image" && !isItunes -> inChannelImage = true
                    inChannelImage && tag == "url" -> if (feedArtwork == null) feedArtwork = parser.safeNextText()

                    tag == "title" -> feedTitle = parser.safeNextText()
                    tag == "description" -> feedDescription = parser.safeNextText()
                }

                XmlPullParser.END_TAG -> when (tag) {
                    "item" -> {
                        if (itemEnclosureUrl.isNotEmpty()) {
                            episodes += EpisodeEntity(
                                guid = itemGuid.ifEmpty { itemEnclosureUrl },
                                podcastFeedUrl = feedUrl,
                                title = itemTitle,
                                enclosureUrl = itemEnclosureUrl,
                                durationMs = itemDurationMs,
                                showNotes = itemDescription,
                                pubDateMs = itemPubDateMs
                            )
                        }
                        resetItem()
                        inItem = false
                    }
                    "image" -> inChannelImage = false
                }
            }

            event = parser.next()
        }

        return ParsedFeed(feedTitle, feedArtwork, feedDescription, episodes)
    }

    // <itunes:duration> comes as "HH:MM:SS", "MM:SS", or plain seconds
    private fun parseDuration(raw: String): Long {
        val parts = raw.trim().split(":")
        return when (parts.size) {
            3 -> parts[0].toLongSafe() * 3_600_000 + parts[1].toLongSafe() * 60_000 + parts[2].toLongSafe() * 1_000
            2 -> parts[0].toLongSafe() * 60_000 + parts[1].toLongSafe() * 1_000
            else -> raw.trim().toLongSafe() * 1_000
        }
    }

    private fun parseDate(raw: String): Long {
        val trimmed = raw.trim()
        for (fmt in dateFormats) {
            try { return fmt.parse(trimmed)?.time ?: 0L } catch (_: Exception) {}
        }
        return 0L
    }

    private fun String.toLongSafe() = trim().toLongOrNull() ?: 0L

    // nextText() positions parser at END_TAG; safe wrapper swallows mixed-content exceptions
    private fun XmlPullParser.safeNextText(): String = try { nextText() } catch (_: Exception) { "" }
}
