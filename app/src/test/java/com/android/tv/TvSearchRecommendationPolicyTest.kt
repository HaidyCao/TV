package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSearchRecommendationPolicyTest {

    @Test
    fun select_places_favorites_first_and_removes_them_from_the_common_row() {
        val favorite = liveChannel(1)
        val common = liveChannel(2)
        val groups = linkedMapOf(
            "新闻" to listOf(favorite, favorite.copy(), common, common.copy()),
            "体育" to listOf(liveChannel(3))
        )

        val recommendations = TvSearchRecommendationPolicy.select(
            groups,
            setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(favorite)))
        )

        assertEquals(listOf(favorite), recommendations.favorites)
        assertEquals("新闻", recommendations.commonGroupName)
        assertEquals(listOf(common), recommendations.commonChannels)
    }

    @Test
    fun select_uses_the_first_non_empty_playable_group_when_there_are_no_favorites() {
        val firstAvailable = liveChannel(3)
        val groups = linkedMapOf(
            "空分组" to emptyList(),
            "无可播频道" to listOf(Movie(id = 2, title = "失效", studio = Movie.LIVE_STUDIO)),
            "体育" to listOf(firstAvailable),
            "新闻" to listOf(liveChannel(4))
        )

        val recommendations = TvSearchRecommendationPolicy.select(groups, emptySet())

        assertEquals(emptyList<Movie>(), recommendations.favorites)
        assertEquals("体育", recommendations.commonGroupName)
        assertEquals(listOf(firstAvailable), recommendations.commonChannels)
    }

    @Test
    fun select_returns_empty_rows_when_channels_and_favorites_are_empty() {
        val recommendations = TvSearchRecommendationPolicy.select(
            groups = emptyMap(),
            favoriteKeys = setOf("stale favorite")
        )

        assertEquals(emptyList<Movie>(), recommendations.favorites)
        assertEquals(emptyList<Movie>(), recommendations.recent)
        assertEquals(null, recommendations.commonGroupName)
        assertEquals(emptyList<Movie>(), recommendations.commonChannels)
    }

    @Test
    fun empty_search_orders_recent_before_favorites_and_excludes_both_from_common_group() {
        val recent = liveChannel(1)
        val favorite = liveChannel(2)
        val common = liveChannel(3)
        val recommendations = TvSearchRecommendationPolicy.select(
            groups = linkedMapOf("新闻" to listOf(recent, favorite, common)),
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(favorite))),
            recentChannels = listOf(recent)
        )

        assertEquals(listOf(recent), recommendations.recent)
        assertEquals(listOf(favorite), recommendations.favorites)
        assertEquals(listOf(common), recommendations.commonChannels)
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
