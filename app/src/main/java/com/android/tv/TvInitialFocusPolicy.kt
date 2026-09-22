package com.android.tv

/** Describes the first row and item that should receive focus on the TV home screen. */
data class TvInitialFocusTarget(
    val rowIndex: Int,
    val itemIndex: Int
)

/** Pure selection policy for the first usable TV home row. */
internal object TvInitialFocusPolicy {

    fun choose(
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>
    ): TvInitialFocusTarget? {
        val channels = groups.values.flatten()
        val favorites = FavoriteChannelResolver.resolve(channels, favoriteKeys)
        if (favorites.isNotEmpty()) {
            return TvInitialFocusTarget(rowIndex = 0, itemIndex = 0)
        }

        val firstPlayableGroupIndex = groups.values.indexOfFirst { group ->
            group.any(::isPlayableLive)
        }
        if (firstPlayableGroupIndex < 0) return null

        return TvInitialFocusTarget(
            rowIndex = firstPlayableGroupIndex,
            itemIndex = groups.values.elementAt(firstPlayableGroupIndex).indexOfFirst(::isPlayableLive)
        )
    }

    private fun isPlayableLive(channel: Movie): Boolean {
        return channel.isLive && !channel.videoUrl.isNullOrBlank()
    }
}
