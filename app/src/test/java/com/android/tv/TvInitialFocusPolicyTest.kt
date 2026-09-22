package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvInitialFocusPolicyTest {

    @Test
    fun choose_prefers_the_favorites_row() {
        val favorite = liveChannel(1)
        val groups = linkedMapOf(
            "新闻" to listOf(liveChannel(2)),
            "体育" to listOf(favorite)
        )

        val target = TvInitialFocusPolicy.choose(
            groups,
            setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(favorite)))
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 0, itemIndex = 0), target)
    }

    @Test
    fun choose_skips_empty_and_unplayable_groups() {
        val playable = liveChannel(3)
        val groups = linkedMapOf(
            "空分组" to emptyList(),
            "无效分组" to listOf(
                Movie(id = 4, title = "断流", studio = Movie.LIVE_STUDIO),
                Movie(id = 5, title = "点播", studio = "VOD", videoUrl = "https://example.test/vod")
            ),
            "可播放分组" to listOf(
                Movie(id = 6, title = "无效频道", studio = Movie.LIVE_STUDIO),
                playable
            )
        )

        val target = TvInitialFocusPolicy.choose(groups, emptySet())

        assertEquals(TvInitialFocusTarget(rowIndex = 2, itemIndex = 1), target)
    }

    @Test
    fun choose_returns_null_when_no_group_has_a_playable_live_channel() {
        val groups = linkedMapOf(
            "空分组" to emptyList(),
            "不可播放" to listOf(
                Movie(id = 7, title = "缺少地址", studio = Movie.LIVE_STUDIO),
                Movie(id = 8, title = "点播", studio = "VOD", videoUrl = "https://example.test/vod")
            )
        )

        assertNull(TvInitialFocusPolicy.choose(groups, emptySet()))
    }

    private fun liveChannel(id: Long): Movie {
        return Movie(
            id = id,
            title = "频道 $id",
            videoUrl = "https://example.test/live/$id",
            studio = Movie.LIVE_STUDIO
        )
    }
}
