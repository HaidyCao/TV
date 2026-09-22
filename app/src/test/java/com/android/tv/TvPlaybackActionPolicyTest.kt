package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPlaybackActionPolicyTest {

    @Test
    fun next_channel_is_visible_and_primary_when_an_adjacent_channel_exists() {
        val first = liveChannel(1)
        val second = liveChannel(2)

        val actions = TvPlaybackActionPolicy.resolve(
            currentChannel = first,
            channels = listOf(first, second),
            canRetry = true
        )

        assertTrue(actions.hasNextChannel)
        assertTrue(actions.canRetry)
        assertEquals(TvPlaybackPrimaryAction.NEXT_CHANNEL, actions.primaryAction)
    }

    @Test
    fun retry_is_the_fallback_when_no_adjacent_channel_exists() {
        val onlyChannel = liveChannel(1)

        val actions = TvPlaybackActionPolicy.resolve(
            currentChannel = onlyChannel,
            channels = listOf(onlyChannel),
            canRetry = true
        )

        assertFalse(actions.hasNextChannel)
        assertEquals(TvPlaybackPrimaryAction.RETRY, actions.primaryAction)
    }

    @Test
    fun back_is_the_only_fallback_for_an_invalid_channel() {
        val actions = TvPlaybackActionPolicy.resolve(
            currentChannel = null,
            channels = emptyList(),
            canRetry = false
        )

        assertFalse(actions.hasNextChannel)
        assertEquals(TvPlaybackPrimaryAction.BACK, actions.primaryAction)
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
