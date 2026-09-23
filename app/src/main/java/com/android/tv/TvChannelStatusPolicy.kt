package com.android.tv

/** A short, non-focusable status message shown above the TV browse rows. */
data class TvChannelStatus(
    val messageResId: Int,
    val style: Style
) {
    enum class Style {
        PROGRESS,
        INFO,
        EMPTY,
        ERROR
    }
}

/** Converts repository states into concise TV status bar messages. */
object TvChannelStatusPolicy {
    fun presentation(state: ChannelState): TvChannelStatus? = when (state) {
        ChannelState.Idle -> TvChannelStatus(R.string.tv_status_loading, TvChannelStatus.Style.PROGRESS)
        is ChannelState.Loading -> TvChannelStatus(
            if (state.groups.isEmpty()) R.string.tv_status_loading else R.string.tv_status_refreshing,
            TvChannelStatus.Style.PROGRESS
        )
        is ChannelState.Content -> if (state.fromSnapshot) {
            TvChannelStatus(R.string.tv_status_cached, TvChannelStatus.Style.INFO)
        } else {
            null
        }
        is ChannelState.Empty -> TvChannelStatus(
            if (state.fromSnapshot) R.string.tv_status_empty_cached else R.string.tv_status_empty,
            TvChannelStatus.Style.EMPTY
        )
        is ChannelState.Error -> TvChannelStatus(
            if (state.groups.isEmpty()) {
                R.string.tv_status_error
            } else {
                R.string.tv_status_error_with_channels
            },
            TvChannelStatus.Style.ERROR
        )
    }
}
