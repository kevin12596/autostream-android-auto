package com.kcliang.autostream

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSearchTest {
    @Test fun emptyOrWhitespaceInputDoesNotNavigate() {
        listOf("", " ", "\t\r\n", "\u3000\u00a0").forEach { assertNull(YouTubeSearch.url(it)) }
    }

    @Test fun chineseAndDictatedInputRoundTripsWithoutLosingCharacters() {
        assertQuery("周杰倫 晴天", "周杰倫 晴天")
        assertQuery("  台北天氣預報\n", "台北天氣預報")
        assertQuery("\u3000爵士樂 🎷\u3000", "爵士樂 🎷")
    }

    @Test fun punctuationStaysInsideOneQueryParameter() {
        listOf("R&B #live?", "C++ 100%", "a&v=other#fragment", "\"');alert(1);//", "https://example.com/?a=b&c=d")
            .forEach { assertQuery(it, it) }
    }

    @Test fun internalSpacesAndCaseArePreserved() {
        assertQuery("  BMW  iX 2026  ", "BMW  iX 2026")
    }

    @Test fun resultsCanBeRecognizedAfterYouTubeRedirects() {
        assertEquals("R&B 爵士", YouTubeSearch.queryFromUrl(YouTubeSearch.url("R&B 爵士")))
        assertEquals("R&B", YouTubeSearch.queryFromUrl("https://www.youtube.com/results?sp=abc&search_query=R%26B&app=m"))
        listOf(null, "https://m.youtube.com/watch?v=abcdefg", "https://youtube.com.evil/results?search_query=x",
            "https://m.youtube.com/results?search_query=%invalid", "https://m.youtube.com/results?search_query=")
            .forEach { assertNull(YouTubeSearch.queryFromUrl(it)) }
    }

    private fun assertQuery(input: String, expected: String) {
        val uri = URI(YouTubeSearch.url(input)!!)
        assertEquals("https", uri.scheme)
        assertEquals("m.youtube.com", uri.host)
        assertEquals("/results", uri.path)
        assertNull(uri.fragment)
        val parameters = uri.rawQuery.split('&')
        assertEquals(1, parameters.size)
        assertEquals("search_query", parameters.single().substringBefore('='))
        assertEquals(expected, URLDecoder.decode(parameters.single().substringAfter('='), "UTF-8"))
    }
}
