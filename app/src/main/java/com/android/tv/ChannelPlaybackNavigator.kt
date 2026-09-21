package com.android.tv

/** Pure channel-order logic for live playback controls. */
internal object ChannelPlaybackNavigator {

    fun adjacent(
        current: Movie,
        channels: List<Movie>,
        direction: Int
    ): Movie? {
        val playable = channels.filter { channel ->
            channel.isLive && !channel.videoUrl.isNullOrBlank()
        }
        if (playable.size < 2) return null

        val currentIndex = playable.indexOfFirst { channel -> channel.id == current.id }
        if (currentIndex == -1) return null

        val step = if (direction >= 0) 1 else -1
        val targetIndex = (currentIndex + step + playable.size) % playable.size
        return playable[targetIndex]
    }
}
