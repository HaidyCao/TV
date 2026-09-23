package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvSettingsSourcePolicyTest {

    @Test
    fun online_and_snapshot_content_report_playable_count_and_update_time() {
        val online = TvSettingsSourcePolicy.presentation(content(fromSnapshot = false))
        assertEquals(TvSettingsSourcePresentation.State.ONLINE, online.state)
        assertEquals(1, online.playableChannelCount)
        assertEquals(1234L, online.updatedAtMillis)

        val cached = TvSettingsSourcePolicy.presentation(content(fromSnapshot = true))
        assertEquals(TvSettingsSourcePresentation.State.CACHED, cached.state)
        assertEquals(1, cached.playableChannelCount)
        assertEquals(1234L, cached.updatedAtMillis)
    }

    @Test
    fun loading_and_failure_keep_existing_playable_channel_count() {
        val loading = TvSettingsSourcePolicy.presentation(ChannelState.Loading(groups()))
        assertEquals(TvSettingsSourcePresentation.State.REFRESHING, loading.state)
        assertEquals(1, loading.playableChannelCount)
        assertNull(loading.updatedAtMillis)

        val failure = TvSettingsSourcePolicy.presentation(ChannelState.Error("offline", groups()))
        assertEquals(TvSettingsSourcePresentation.State.ERROR_WITH_CHANNELS, failure.state)
        assertEquals(1, failure.playableChannelCount)
    }

    @Test
    fun only_live_channels_with_urls_are_included_and_empty_states_are_distinct() {
        val nonPlayable = mapOf(
            "news" to listOf(
                Movie(title = "missing url", studio = Movie.LIVE_STUDIO),
                Movie(title = "movie", videoUrl = "https://example.com/movie.m3u8")
            )
        )
        val failed = TvSettingsSourcePolicy.presentation(ChannelState.Error("offline", nonPlayable))
        assertEquals(TvSettingsSourcePresentation.State.ERROR, failed.state)
        assertEquals(0, failed.playableChannelCount)

        assertEquals(
            TvSettingsSourcePresentation.State.EMPTY,
            TvSettingsSourcePolicy.presentation(ChannelState.Empty("source", fromSnapshot = false)).state
        )
        assertEquals(
            TvSettingsSourcePresentation.State.EMPTY_CACHED,
            TvSettingsSourcePolicy.presentation(ChannelState.Empty("source", fromSnapshot = true)).state
        )
    }

    private fun content(fromSnapshot: Boolean) = ChannelState.Content(
        groups = groups(),
        sourceUrl = "source",
        fromSnapshot = fromSnapshot,
        updatedAtMillis = 1234L
    )

    private fun groups() = mapOf(
        "新闻" to listOf(
            Movie(
                title = "频道",
                videoUrl = "https://example.com/live.m3u8",
                studio = Movie.LIVE_STUDIO
            )
        )
    )
}
