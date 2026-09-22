package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusRestorePolicyTest {

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
