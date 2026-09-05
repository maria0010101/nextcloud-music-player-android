package com.nextcloud.musicplayer.core.network

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URLDecoder

object WebDavXmlParser {

    fun parse(inputStream: InputStream): List<WebDavItem> {
        val items = mutableListOf<WebDavItem>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, "UTF-8")
        }

        var eventType = parser.eventType
        var currentHref: String? = null
        var currentDisplayName: String? = null
        var isCollection = false
        var contentLength: Long = 0L
        var contentType: String? = null
        var lastModified: String? = null
        var etag: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name?.substringAfter(':')?.lowercase()

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (tagName) {
                        "response" -> {
                            currentHref = null
                            currentDisplayName = null
                            isCollection = false
                            contentLength = 0L
                            contentType = null
                            lastModified = null
                            etag = null
                        }
                        "href" -> {
                            currentHref = parser.nextText()
                        }
                        "displayname" -> {
                            currentDisplayName = parser.nextText()
                        }
                        "collection" -> {
                            isCollection = true
                        }
                        "getcontentlength" -> {
                            contentLength = parser.nextText()?.toLongOrNull() ?: 0L
                        }
                        "getcontenttype" -> {
                            contentType = parser.nextText()
                        }
                        "getlastmodified" -> {
                            lastModified = parser.nextText()
                        }
                        "getetag" -> {
                            etag = parser.nextText()?.trim('"')
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (tagName == "response") {
                        currentHref?.let { href ->
                            val decodedHref = try {
                                URLDecoder.decode(href, "UTF-8")
                            } catch (e: Exception) {
                                href
                            }
                            val fallbackName = decodedHref.trimEnd('/').substringAfterLast('/')
                            val displayName = currentDisplayName?.ifBlank { fallbackName } ?: fallbackName

                            items.add(
                                WebDavItem(
                                    href = href,
                                    displayName = displayName,
                                    isCollection = isCollection,
                                    contentLength = contentLength,
                                    contentType = contentType,
                                    lastModified = lastModified,
                                    etag = etag
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return items
    }
}
