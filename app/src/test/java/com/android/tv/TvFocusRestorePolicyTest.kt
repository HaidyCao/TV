package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusRestorePolicyTest {

    @Test
    fun capture_and_restore_follow_the_channel_when_favorites_row_is_inserted() {
        val selected = liveChannel(2)
        val groups = linkedMapOf(
            "新闻" to listOf(liveChannel(1), selected),
            "体育" to listOf(liveChannel(3))
        )

        val saved = TvFocusRestorePolicy.capture(groups, selected, previous = null)
        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(selected))),
            savedPosition = saved
        )

        assertEquals(
            TvSavedFocusPosition(
                channelKey = requireNotNull(ChannelFavorites.favoriteKeyFor(selected)),
                groupName = "新闻",
                rowKind = TvFocusRowKind.ORIGINAL_GROUP
            ),
            saved
        )
        assertEquals(TvInitialFocusTarget(rowIndex = 1, itemIndex = 1), target)
    }

    @Test
    fun restores_channel_in_original_group_with_favorites_row_offset() {
        val selected = liveChannel(2)
        val groups = linkedMapOf(
            "新闻" to listOf(liveChannel(1), selected),
            "体育" to listOf(liveChannel(3))
        )

        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(selected))),
            savedPosition = savedPosition(
                selected,
                groupName = "新闻",
                rowKind = TvFocusRowKind.ORIGINAL_GROUP
            )
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 1, itemIndex = 1), target)
    }

    @Test
    fun favorite_row_duplicate_prefers_the_original_group() {
        val selected = liveChannel(2)
        val groups = linkedMapOf(
            "新闻" to listOf(liveChannel(1), selected),
            "体育" to listOf(liveChannel(3))
        )

        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(selected))),
            savedPosition = savedPosition(
                selected,
                groupName = "新闻",
                rowKind = TvFocusRowKind.FAVORITES
            )
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 1, itemIndex = 1), target)
    }

    @Test
    fun original_group_without_favorites_keeps_group_row_index() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("新闻" to listOf(liveChannel(1), selected))

        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = emptySet(),
            savedPosition = savedPosition(
                selected,
                groupName = "新闻",
                rowKind = TvFocusRowKind.ORIGINAL_GROUP
            )
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 0, itemIndex = 1), target)
    }

    @Test
    fun missing_original_group_returns_null_for_initial_focus_fallback() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("体育" to listOf(liveChannel(3)))

        assertNull(
            TvFocusRestorePolicy.choose(
                groups = groups,
                favoriteKeys = emptySet(),
                savedPosition = savedPosition(
                    selected,
                    groupName = "新闻",
                    rowKind = TvFocusRowKind.ORIGINAL_GROUP
                )
            )
        )
    }

    @Test
    fun removed_channel_returns_null_even_when_group_remains() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("新闻" to listOf(liveChannel(1), liveChannel(3)))

        assertNull(
            TvFocusRestorePolicy.choose(
                groups = groups,
                favoriteKeys = emptySet(),
                savedPosition = savedPosition(
                    selected,
                    groupName = "新闻",
                    rowKind = TvFocusRowKind.ORIGINAL_GROUP
                )
            )
        )
    }

    @Test
    fun moved_channel_does_not_restore_through_favorites_when_original_group_is_gone() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("体育" to listOf(selected))

        assertNull(
            TvFocusRestorePolicy.choose(
                groups = groups,
                favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(selected))),
                savedPosition = savedPosition(
                    selected,
                    groupName = "新闻",
                    rowKind = TvFocusRowKind.FAVORITES
                )
            )
        )
    }

    @Test
    fun favorite_only_position_can_restore_when_original_group_is_unknown() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("新闻" to listOf(liveChannel(1), selected))

        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(selected))),
            savedPosition = savedPosition(
                selected,
                groupName = null,
                rowKind = TvFocusRowKind.FAVORITES
            )
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 0, itemIndex = 0), target)
    }

    @Test
    fun later_group_restore_accounts_for_favorites_and_recent_rows() {
        val selected = liveChannel(3)
        val first = liveChannel(1)
        val second = liveChannel(2)
        val groups = linkedMapOf("央视频道" to listOf(first), "卫视频道" to listOf(second, selected))
        val recent = listOf(liveChannel(4))

        val withoutOptionalRows = TvFocusRestorePolicy.choose(
            groups, emptySet(), savedPosition(selected, "卫视频道", TvFocusRowKind.ORIGINAL_GROUP)
        )
        val withRecent = TvFocusRestorePolicy.choose(
            groups, emptySet(), savedPosition(selected, "卫视频道", TvFocusRowKind.ORIGINAL_GROUP), recent
        )
        val withFavoritesAndRecent = TvFocusRestorePolicy.choose(
            groups,
            setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(first))),
            savedPosition(selected, "卫视频道", TvFocusRowKind.ORIGINAL_GROUP),
            recent
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 1, itemIndex = 1), withoutOptionalRows)
        assertEquals(TvInitialFocusTarget(rowIndex = 2, itemIndex = 1), withRecent)
        assertEquals(TvInitialFocusTarget(rowIndex = 3, itemIndex = 1), withFavoritesAndRecent)
    }

    @Test
    fun recent_row_focus_restores_to_the_recent_row_when_it_exists() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("央视频道" to listOf(liveChannel(1)), "体育" to listOf(selected))
        val target = TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = setOf(requireNotNull(ChannelFavorites.favoriteKeyFor(liveChannel(1)))),
            savedPosition = savedPosition(selected, null, TvFocusRowKind.RECENT),
            recentChannels = listOf(liveChannel(3), selected)
        )

        assertEquals(TvInitialFocusTarget(rowIndex = 2, itemIndex = 1), target)
    }

    @Test
    fun recent_focus_keeps_original_group_as_fallback_after_recent_row_is_removed() {
        val selected = liveChannel(2)
        val groups = linkedMapOf("央视频道" to listOf(liveChannel(1)), "体育" to listOf(selected))
        val saved = TvFocusRestorePolicy.capture(
            groups, selected, previous = null, rowKind = TvFocusRowKind.RECENT
        )

        assertEquals("体育", saved?.groupName)
        assertEquals(TvInitialFocusTarget(rowIndex = 1, itemIndex = 0), TvFocusRestorePolicy.choose(
            groups = groups,
            favoriteKeys = emptySet(),
            savedPosition = saved,
            recentChannels = emptyList()
        ))
    }

    @Test
    fun initial_focus_offsets_later_groups_only_when_recent_row_is_present() {
        val groups = linkedMapOf(
            "空组" to emptyList(),
            "体育" to listOf(liveChannel(2))
        )

        assertEquals(TvInitialFocusTarget(1, 0), TvInitialFocusPolicy.choose(groups, emptySet()))
        assertEquals(TvInitialFocusTarget(2, 0), TvInitialFocusPolicy.choose(
            groups, emptySet(), hasRecentRow = true
        ))
    }

    private fun savedPosition(
        movie: Movie,
        groupName: String?,
        rowKind: TvFocusRowKind
    ): TvSavedFocusPosition {
        return TvSavedFocusPosition(
            channelKey = requireNotNull(ChannelFavorites.favoriteKeyFor(movie)),
            groupName = groupName,
            rowKind = rowKind
        )
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
