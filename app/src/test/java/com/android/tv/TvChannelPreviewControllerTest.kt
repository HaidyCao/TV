package com.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChannelPreviewControllerTest {

    @Test
    fun a_new_focus_generation_invalidates_a_delayed_preview() {
        val generations = TvChannelPreviewGeneration()
        val first = generations.next()
        val second = generations.next()

        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun only_live_channels_with_a_url_can_be_previewed() {
        val live = Movie(
            title = "新闻",
            videoUrl = "https://example.test/live.m3u8",
            studio = Movie.LIVE_STUDIO
        )
        val vod = live.copy(studio = "VOD")
        val missingUrl = live.copy(videoUrl = " ")

        assertTrue(TvChannelPreviewPolicy.canPreview(live))
        assertFalse(TvChannelPreviewPolicy.canPreview(vod))
        assertFalse(TvChannelPreviewPolicy.canPreview(missingUrl))
    }

    @Test
    fun focus_delay_is_long_enough_to_ignore_quick_navigation() {
        assertTrue(TvChannelPreviewPolicy.FOCUS_DELAY_MS in 700L..900L)
    }
}
