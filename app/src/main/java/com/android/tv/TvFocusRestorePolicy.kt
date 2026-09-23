package com.android.tv

/** Identifies the row from which a TV home focus position was captured. */
internal enum class TvFocusRowKind {
    ORIGINAL_GROUP,
    FAVORITES,
    RECENT
}

/** Stable information needed to restore a channel focus after the home view is rebuilt. */
internal data class TvSavedFocusPosition(
    val channelKey: String,
    val groupName: String?,
    val rowKind: TvFocusRowKind
)

/** Resolves a saved TV home position into the row/item coordinates used by BrowseSupportFragment. */
internal object TvFocusRestorePolicy {

    /** Captures a stable channel identity before the rows are rebuilt. */
    fun capture(
        groups: Map<String, List<Movie>>,
        channel: Movie,
        previous: TvSavedFocusPosition?,
        rowKind: TvFocusRowKind? = null
    ): TvSavedFocusPosition? {
        val channelKey = ChannelFavorites.favoriteKeyFor(channel) ?: return null
        val resolvedRowKind = rowKind ?: if (previous?.channelKey == channelKey) {
            previous.rowKind
        } else {
            TvFocusRowKind.ORIGINAL_GROUP
        }
        val groupName = if (resolvedRowKind != TvFocusRowKind.FAVORITES) groups.entries.firstOrNull { (_, channels) ->
            channels.any { candidate ->
                ChannelFavorites.favoriteKeyFor(candidate) == channelKey
            }
        }?.key else null
        return TvSavedFocusPosition(
            channelKey = channelKey,
            groupName = groupName,
            rowKind = resolvedRowKind
        )
    }

    fun choose(
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>,
        savedPosition: TvSavedFocusPosition?,
        recentChannels: List<Movie> = emptyList()
    ): TvInitialFocusTarget? {
        savedPosition ?: return null

        val hasFavorites = TvHomeRowLayoutPolicy.hasFavorites(groups, favoriteKeys)
        if (savedPosition.rowKind == TvFocusRowKind.RECENT) {
            val recentIndex = recentChannels.indexOfFirst { channel ->
                ChannelFavorites.favoriteKeyFor(channel) == savedPosition.channelKey
            }
            if (recentIndex >= 0) {
                return TvInitialFocusTarget(
                    rowIndex = TvHomeRowLayoutPolicy.recentRowIndex(hasFavorites),
                    itemIndex = recentIndex
                )
            }
        }

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
                    rowIndex = TvHomeRowLayoutPolicy.originalGroupRowIndex(
                        originalGroupIndex = originalGroupIndex,
                        hasFavorites = hasFavorites,
                        hasRecentRow = recentChannels.isNotEmpty()
                    ),
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
                return TvInitialFocusTarget(
                    rowIndex = TvHomeRowLayoutPolicy.favoritesRowIndex(),
                    itemIndex = favoriteIndex
                )
            }
        }

        return null
    }
}
