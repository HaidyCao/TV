package com.android.tv

/** Identifies the row from which a TV home focus position was captured. */
internal enum class TvFocusRowKind {
    ORIGINAL_GROUP,
    FAVORITES
}

/** Stable information needed to restore a channel focus after the home view is rebuilt. */
internal data class TvSavedFocusPosition(
    val channelKey: String,
    val groupName: String?,
    val rowKind: TvFocusRowKind
)

/** Resolves a saved TV home position into the row/item coordinates used by BrowseSupportFragment. */
internal object TvFocusRestorePolicy {

    fun choose(
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>,
        savedPosition: TvSavedFocusPosition?
    ): TvInitialFocusTarget? {
        savedPosition ?: return null

        val originalGroupIndex = groups.entries.indexOfFirst { (groupName, channels) ->
            groupName == savedPosition.groupName &&
                channels.any { channel ->
                    ChannelFavorites.favoriteKeyFor(channel) == savedPosition.channelKey
                }
        }
        if (originalGroupIndex >= 0) {
            val channels = groups.entries.elementAt(originalGroupIndex).value
            val itemIndex = channels.indexOfFirst { channel ->
                ChannelFavorites.favoriteKeyFor(channel) == savedPosition.channelKey
            }
            if (itemIndex >= 0) {
                return TvInitialFocusTarget(
                    rowIndex = originalGroupIndex + favoriteRowOffset(groups, favoriteKeys),
                    itemIndex = itemIndex
                )
            }
        }

        if (savedPosition.rowKind == TvFocusRowKind.FAVORITES && savedPosition.groupName == null) {
            val favoriteIndex = FavoriteChannelResolver.resolve(
                groups.values.flatten(),
                favoriteKeys
            ).indexOfFirst { channel ->
                ChannelFavorites.favoriteKeyFor(channel) == savedPosition.channelKey
            }
            if (favoriteIndex >= 0) {
                return TvInitialFocusTarget(rowIndex = 0, itemIndex = favoriteIndex)
            }
        }

        return null
    }

    private fun favoriteRowOffset(
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>
    ): Int {
        return if (FavoriteChannelResolver.resolve(groups.values.flatten(), favoriteKeys).isEmpty()) {
            0
        } else {
            1
        }
    }
}
