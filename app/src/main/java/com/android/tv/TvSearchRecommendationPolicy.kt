package com.android.tv

internal data class TvSearchRecommendations(
    val recent: List<Movie>,
    val favorites: List<Movie>,
    val commonGroupName: String?,
    val commonChannels: List<Movie>
)

/** Selects the empty-query rows shown by the Android TV search screen. */
internal object TvSearchRecommendationPolicy {

    fun select(
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>,
        recentChannels: List<Movie> = emptyList()
    ): TvSearchRecommendations {
        val channels = groups.values.flatten()
        val recent = recentChannels
            .asSequence()
            .filter(::isPlayableLiveChannel)
            .distinctBy(::deduplicationKey)
            .toList()
        val favorites = FavoriteChannelResolver.resolve(channels, favoriteKeys)
            .distinctBy(::deduplicationKey)
        val favoriteChannelKeys = favorites.mapNotNull(ChannelFavorites::favoriteKeyFor).toSet()
        val recentChannelKeys = recent.mapNotNull(ChannelFavorites::favoriteKeyFor).toSet()

        val commonGroup = groups.entries.firstNotNullOfOrNull { (name, groupChannels) ->
            val commonChannels = groupChannels
                .asSequence()
                .filter(::isPlayableLiveChannel)
                .filterNot { channel ->
                    ChannelFavorites.favoriteKeyFor(channel)?.let { key ->
                        key in favoriteChannelKeys || key in recentChannelKeys
                    } == true
                }
                .distinctBy(::deduplicationKey)
                .toList()
            if (commonChannels.isEmpty()) null else name to commonChannels
        }

        return TvSearchRecommendations(
            recent = recent,
            favorites = favorites,
            commonGroupName = commonGroup?.first,
            commonChannels = commonGroup?.second.orEmpty()
        )
    }

    private fun isPlayableLiveChannel(channel: Movie): Boolean {
        return channel.isLive && !channel.videoUrl.isNullOrBlank()
    }

    private fun deduplicationKey(channel: Movie): Any {
        return ChannelFavorites.favoriteKeyFor(channel) ?: channel
    }
}
