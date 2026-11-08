package com.kcliang.autostream

import org.junit.Assert.*
import org.junit.Test

class YouTubeNavigationTest {
    @Test fun preservesVideoPlaylistIndexAndTimestampOnMobileSite() {
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk&list=PL123&index=2&t=30s",
            YouTubeNavigation.videoUrl("https://www.youtube.com/watch?v=abcdefghijk&list=PL123&index=2&t=30s&feature=share"))
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk", YouTubeNavigation.videoUrl("https://youtu.be/abcdefghijk"))
        assertEquals("https://m.youtube.com/watch?v=abcdefghijk", YouTubeNavigation.videoUrl("https://m.youtube.com/shorts/abcdefghijk"))
    }

    @Test fun doesNotForwardSearchesLoginOrUntrustedUrls() {
        listOf(null, "javascript:alert(1)", "https://m.youtube.com.evil/watch?v=abcdefghijk",
            "https://m.youtube.com/results?search_query=music", "https://accounts.google.com/",
            "https://m.youtube.com@evil/watch?v=abcdefghijk", "https://m.youtube.com/watch?v=bad",
            "https://m.youtube.com:444/watch?v=abcdefghijk").forEach { assertNull(YouTubeNavigation.videoUrl(it)) }
    }
}
