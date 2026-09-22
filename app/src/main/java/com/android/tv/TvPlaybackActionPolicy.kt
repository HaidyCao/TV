package com.android.tv

/** The action that should receive focus first on the TV playback status card. */
internal enum class TvPlaybackPrimaryAction {
    NEXT_CHANNEL,
    RETRY,
    BACK
}

internal data class TvPlaybackActionAvailability(
    val hasNextChannel: Boolean,
    val canRetry: Boolean,
    val primaryAction: TvPlaybackPrimaryAction
)

/** Pure TV-only action selection for playback errors and ended streams. */
internal object TvPlaybackActionPolicy {

    fun resolve(
        currentChannel: Movie?,
        channels: List<Movie>,
        canRetry: Boolean
    ): TvPlaybackActionAvailability {
        val hasNextChannel = currentChannel?.let { current ->
            ChannelPlaybackNavigator.adjacent(
                current = current,
                channels = channels,
                direction = PlaybackChannelKeyPolicy.NEXT_CHANNEL
            ) != null
        } ?: false

        val primaryAction = when {
            hasNextChannel -> TvPlaybackPrimaryAction.NEXT_CHANNEL
            canRetry -> TvPlaybackPrimaryAction.RETRY
            else -> TvPlaybackPrimaryAction.BACK
        }
        return TvPlaybackActionAvailability(
            hasNextChannel = hasNextChannel,
            canRetry = canRetry,
            primaryAction = primaryAction
        )
    }
}
