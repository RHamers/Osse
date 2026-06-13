package com.rhamers.osse.rss

import com.rhamers.osse.model.Podcast

object OpmlWriter {

    fun generate(podcasts: List<Podcast>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<opml version=\"1.0\">")
        appendLine("  <head><title>Ossë Subscriptions</title></head>")
        appendLine("  <body>")
        podcasts.forEach { podcast ->
            appendLine(
                "    <outline text=\"${escape(podcast.title)}\" title=\"${escape(podcast.title)}\"" +
                " type=\"rss\" xmlUrl=\"${escape(podcast.feedUrl)}\"/>"
            )
        }
        appendLine("  </body>")
        append("</opml>")
    }

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
