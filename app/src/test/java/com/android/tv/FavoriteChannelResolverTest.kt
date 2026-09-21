package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FavoriteChannelResolverTest {

    @Test
    fun resolve_keeps_playlist_order_and_ignores_stale_or_unplayable_favorites() {
        val first = liveChannel(1)
        val second = liveChannel(2)
        val channels = listOf(
            first,
            Movie(id = 3, videoUrl = null, studio = Movie.LIVE_STUDIO),
            Movie(id = 4, videoUrl = "https://example.test/vod", studio = "VOD"),
            second
        )

        val favorites = FavoriteChannelResolver.resolve(
            channels,
            setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(second)), "stale channel")
        )

        assertEquals(listOf(second), favorites)
    }

    @Test
    fun resolve_returns_empty_when_no_favorite_keys_are_saved() {
        assertEquals(emptyList<Movie>(), FavoriteChannelResolver.resolve(listOf(liveChannel(1)), emptySet()))
    }

    @Test
    fun favorite_key_ignores_stream_url_and_normalizes_channel_name() {
        val original = TvDataManager.parsePlaylist(
            "CCTV 1 高清,https://old.example.test/live"
        ).values.flatten().single()
        val replacement = TvDataManager.parsePlaylist(
            "  cctv   1 高清 ,https://new.example.test/live"
        ).values.flatten().single()

        assertEquals(
            ChannelFavorites.favoriteKeyFor(original),
            ChannelFavorites.favoriteKeyFor(replacement)
        )
    }

    @Test
    fun duplicate_titles_get_distinct_favorite_keys_and_do_not_link() {
        val channels = TvDataManager.parsePlaylist(
            "新闻,#genre#\n同名频道,https://example.test/live/one\n同名频道,https://example.test/live/two"
        ).values.flatten()
        val first = channels[0]
        val second = channels[1]
        val firstKey = requireNotNull(ChannelFavorites.favoriteKeyFor(first))

        assertNotEquals(firstKey, ChannelFavorites.favoriteKeyFor(second))
        assertEquals(listOf(first), FavoriteChannelResolver.resolve(channels, setOf(firstKey)))
    }

    @Test
    fun duplicate_titles_in_different_groups_get_distinct_favorite_keys() {
        val channels = TvDataManager.parsePlaylist(
            "新闻,#genre#\n同名频道,https://example.test/live/news\n体育,#genre#\n同名频道,https://example.test/live/sports"
        ).values.flatten()

        assertEquals(2, channels.size)
        assertNotEquals(
            ChannelFavorites.favoriteKeyFor(channels[0]),
            ChannelFavorites.favoriteKeyFor(channels[1])
        )
    }

    @Test
    fun identity_encoding_keeps_colon_containing_group_and_title_pairs_distinct() {
        val channels = TvDataManager.parsePlaylist(
            "甲:乙,#genre#\n丙,https://example.test/live/one\n甲,#genre#\n乙:丙,https://example.test/live/two"
        ).values.flatten()

        assertEquals(2, channels.size)
        assertNotEquals(channels[0].id, channels[1].id)
    }

    @Test
    fun same_group_occurrence_keeps_favorite_key_when_both_urls_change() {
        val original = TvDataManager.parsePlaylist(
            "新闻,#genre#\n同名频道,https://old.example.test/live/one\n同名频道,https://old.example.test/live/two"
        ).values.flatten()
        val replacement = TvDataManager.parsePlaylist(
            "新闻,#genre#\n同名频道,https://new.example.test/live/one\n同名频道,https://new.example.test/live/two"
        ).values.flatten()

        assertEquals(
            ChannelFavorites.favoriteKeyFor(original[0]),
            ChannelFavorites.favoriteKeyFor(replacement[0])
        )
        assertEquals(
            ChannelFavorites.favoriteKeyFor(original[1]),
            ChannelFavorites.favoriteKeyFor(replacement[1])
        )
        assertNotEquals(
            ChannelFavorites.favoriteKeyFor(replacement[0]),
            ChannelFavorites.favoriteKeyFor(replacement[1])
        )
    }

    @Test
    fun m3u_tvg_id_keeps_favorite_key_when_stream_url_changes() {
        val original = TvDataManager.parsePlaylist(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"news.one\" group-title=\"新闻\",同名频道\nhttps://old.example.test/live"
        ).values.flatten().single()
        val replacement = TvDataManager.parsePlaylist(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"news.one\" group-title=\"新闻\",频道改名\nhttps://new.example.test/live"
        ).values.flatten().single()

        assertEquals(
            ChannelFavorites.favoriteKeyFor(original),
            ChannelFavorites.favoriteKeyFor(replacement)
        )
    }

    @Test
    fun legacy_title_favorite_migrates_to_one_matching_channel_without_linking_duplicates() {
        val channels = TvDataManager.parsePlaylist(
            "新闻,#genre#\n同名频道,https://example.test/live/one\n同名频道,https://example.test/live/two"
        ).values.flatten()

        val migrated = ChannelFavorites.migrateLegacyKeys(channels, setOf("同名频道"))

        assertEquals(
            setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(channels.first()))),
            migrated
        )
    }

    private fun liveChannel(id: Long): Movie {
        return Movie(
            id = id,
            title = "Channel $id",
            videoUrl = "https://example.test/$id",
            studio = Movie.LIVE_STUDIO
        )
    }
}
