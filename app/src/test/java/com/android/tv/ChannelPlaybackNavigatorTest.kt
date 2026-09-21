package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelPlaybackNavigatorTest {

    @Test
    fun adjacent_wraps_at_both_ends_of_the_live_playlist() {
        val channels = listOf(liveChannel(1), liveChannel(2), liveChannel(3))

        assertEquals(2L, ChannelPlaybackNavigator.adjacent(channels[0], channels, 1)?.id)
        assertEquals(3L, ChannelPlaybackNavigator.adjacent(channels[0], channels, -1)?.id)
        assertEquals(1L, ChannelPlaybackNavigator.adjacent(channels[2], channels, 1)?.id)
    }

    @Test
    fun adjacent_skips_unplayable_and_non_live_items() {
        val first = liveChannel(1)
        val second = liveChannel(2)
        val channels = listOf(
            first,
            Movie(id = 99, videoUrl = null, studio = Movie.LIVE_STUDIO),
            Movie(id = 100, videoUrl = "https://example.test/vod", studio = "VOD"),
            second
        )

        assertEquals(second.id, ChannelPlaybackNavigator.adjacent(first, channels, 1)?.id)
    }

    @Test
    fun adjacent_returns_null_without_another_playable_channel() {
        val onlyChannel = liveChannel(1)

        assertNull(ChannelPlaybackNavigator.adjacent(onlyChannel, listOf(onlyChannel), 1))
    }

    private fun liveChannel(id: Long): Movie {
        return Movie(
            id = id,
            title = "Channel $id",
            videoUrl = "https://example.test/$id",
            studio = Movie.LIVE_STUDIO
        )
    }
}
