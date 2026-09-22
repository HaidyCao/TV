package com.android.tv

/** Pure visibility state for the channel information layer in playback. */
internal data class PlaybackChannelOverlayState(
    val isVisible: Boolean,
    val autoHideAtMillis: Long? = null
)

internal object PlaybackChannelOverlayPolicy {

    const val AUTO_HIDE_DURATION_MILLIS = 2_500L

    fun hidden(): PlaybackChannelOverlayState = PlaybackChannelOverlayState(isVisible = false)

    fun autoShown(nowMillis: Long): PlaybackChannelOverlayState {
        return PlaybackChannelOverlayState(
            isVisible = true,
            autoHideAtMillis = nowMillis + AUTO_HIDE_DURATION_MILLIS
        )
    }

    fun toggle(
        state: PlaybackChannelOverlayState,
        nowMillis: Long
    ): PlaybackChannelOverlayState {
        return if (isVisibleAt(state, nowMillis)) {
            hidden()
        } else {
            PlaybackChannelOverlayState(isVisible = true)
        }
    }

    fun isVisibleAt(
        state: PlaybackChannelOverlayState,
        nowMillis: Long
    ): Boolean {
        return state.isVisible && (state.autoHideAtMillis == null || nowMillis < state.autoHideAtMillis)
    }
}
