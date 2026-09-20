package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TvDataManagerTest {

    @Test
    fun `txt parser preserves declared group and stable channel id`() {
        val playlist = """
            新闻,#genre#
            频道一,http://example.test/live/one
            频道二,http://example.test/live/two
        """.trimIndent()

        val firstParse = TvDataManager.parsePlaylist(playlist)
        val secondParse = TvDataManager.parsePlaylist(playlist)

        assertEquals(listOf("新闻"), firstParse.keys.toList())
        assertEquals(2, firstParse.getValue("新闻").size)
        assertEquals(Movie.LIVE_STUDIO, firstParse.getValue("新闻").first().studio)
        assertEquals(
            firstParse.getValue("新闻").first().id,
            secondParse.getValue("新闻").first().id
        )
    }

    @Test
    fun `m3u parser preserves explicit group and logo`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://example.test/logo.png" group-title="儿童",测试频道
            https://example.test/live/test.m3u8
        """.trimIndent()

        val channel = TvDataManager.parsePlaylist(playlist).getValue("儿童").single()

        assertEquals("测试频道", channel.title)
        assertEquals("https://example.test/logo.png", channel.cardImageUrl)
        assertEquals("儿童", channel.category)
        assertNotNull(channel.videoUrl)
    }

    @Test
    fun `ungrouped 4k txt channel uses 4k category`() {
        val groups = TvDataManager.parsePlaylist("CCTV 4K,http://example.test/live/4k")

        assertEquals(1, groups.getValue("4K超高清").size)
    }
}
