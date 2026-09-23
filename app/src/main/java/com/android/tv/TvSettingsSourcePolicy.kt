package com.android.tv

/** Source state summary shown on the TV settings page. */
internal data class TvSettingsSourcePresentation(
    val state: State,
    val playableChannelCount: Int,
    val updatedAtMillis: Long?
) {
    enum class State {
        LOADING,
        REFRESHING,
        ONLINE,
        CACHED,
        EMPTY,
        EMPTY_CACHED,
        ERROR,
        ERROR_WITH_CHANNELS
    }
}

/** Converts repository state into the summary and refresh outcome shown in TV settings. */
internal object TvSettingsSourcePolicy {

    fun presentation(state: ChannelState): TvSettingsSourcePresentation = when (state) {
        ChannelState.Idle -> summary(TvSettingsSourcePresentation.State.LOADING)
        is ChannelState.Loading -> summary(
            TvSettingsSourcePresentation.State.REFRESHING,
            groups = state.groups
        )
        is ChannelState.Content -> summary(
            if (state.fromSnapshot) {
                TvSettingsSourcePresentation.State.CACHED
            } else {
                TvSettingsSourcePresentation.State.ONLINE
            },
            groups = state.groups,
            updatedAtMillis = state.updatedAtMillis
        )
        is ChannelState.Empty -> summary(
            if (state.fromSnapshot) {
                TvSettingsSourcePresentation.State.EMPTY_CACHED
            } else {
                TvSettingsSourcePresentation.State.EMPTY
            }
        )
        is ChannelState.Error -> {
            val channelCount = playableChannelCount(state.groups)
            TvSettingsSourcePresentation(
                state = if (channelCount > 0) {
                    TvSettingsSourcePresentation.State.ERROR_WITH_CHANNELS
                } else {
                    TvSettingsSourcePresentation.State.ERROR
                },
                playableChannelCount = channelCount,
                updatedAtMillis = null
            )
        }
    }

    private fun summary(
        state: TvSettingsSourcePresentation.State,
        groups: Map<String, List<Movie>> = emptyMap(),
        updatedAtMillis: Long? = null
    ) = TvSettingsSourcePresentation(
        state = state,
        playableChannelCount = playableChannelCount(groups),
        updatedAtMillis = updatedAtMillis
    )

    private fun playableChannelCount(groups: Map<String, List<Movie>>): Int =
        groups.values.sumOf { channels ->
            channels.count { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
        }
}
