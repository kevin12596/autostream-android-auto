package com.kcliang.autostream

import java.net.URLEncoder
import java.net.URI
import java.net.URLDecoder

/** Both dictated and typed input must be encoded as one YouTube query parameter. */
object YouTubeSearch {
    fun normalize(query: String): String = query.trim()

    fun url(query: String): String? {
        val text = normalize(query)
        if (text.isEmpty()) return null
        return "https://m.youtube.com/results?search_query=" + URLEncoder.encode(text, "UTF-8")
    }

    /** Recognize a completed result page, including redirects and extra YouTube parameters. */
    fun queryFromUrl(url: String?): String? = try {
        val uri = URI(url ?: "")
        if (uri.scheme != "https" || uri.host !in setOf("m.youtube.com", "www.youtube.com", "youtube.com") || uri.path != "/results") {
            null
        } else {
            uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "search_query" }
                ?.substringAfter('=', "")?.let { URLDecoder.decode(it, "UTF-8") }
                ?.let(::normalize)?.takeIf { it.isNotEmpty() }
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
}
