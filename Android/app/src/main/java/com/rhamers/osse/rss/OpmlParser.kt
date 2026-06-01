package com.rhamers.osse.rss

import android.util.Xml
import java.io.InputStream

object OpmlParser {

    fun parse(stream: InputStream): List<String> {
        val urls = linkedSetOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)
        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "outline") {
                val xmlUrl = parser.getAttributeValue(null, "xmlUrl")
                if (!xmlUrl.isNullOrBlank()) urls += xmlUrl
            }
            eventType = parser.next()
        }
        return urls.toList()
    }
}
