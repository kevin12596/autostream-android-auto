package com.kcliang.autostream

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Only forward recognized YouTube videos, never arbitrary URLs from a page. */
object YouTubeNavigation {
    fun videoUrl(raw: String?): String? = try {
        val uri = URI(raw?.takeIf { it.length <= 4096 } ?: "")
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) null else {
            val params = uri.rawQuery.orEmpty().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val id = when (uri.host) {
                "youtu.be" -> uri.path.removePrefix("/")
                "m.youtube.com", "www.youtube.com", "youtube.com" -> when {
                    uri.path == "/watch" -> params["v"]
                    uri.path.startsWith("/shorts/") -> uri.path.removePrefix("/shorts/")
                    else -> null
                }
                else -> null
            }
            if (!YouTubeJs.isValidVideoId(id)) null else buildString {
                append("https://m.youtube.com/watch?v=").append(id)
                for (key in listOf("list", "index", "t", "start")) {
                    params[key]?.takeIf { it.isNotBlank() && it.length <= 256 }?.let {
                        append('&').append(key).append('=').append(URLEncoder.encode(it, "UTF-8"))
                    }
                }
            }
        }
    } catch (_: Exception) { null }
}
