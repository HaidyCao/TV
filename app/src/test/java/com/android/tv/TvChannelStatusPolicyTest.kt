package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvChannelStatusPolicyTest {

    @Test
    fun loading_state_uses_progress_status_with_or_without_existing_channels() {
        assertEquals(
            TvChannelStatus(R.string.tv_status_loading, TvChannelStatus.Style.PROGRESS),
            TvChannelStatusPolicy.presentation(ChannelState.Idle)
        )
        assertEquals(
            TvChannelStatus(R.string.tv_status_refreshing, TvChannelStatus.Style.PROGRESS),
            TvChannelStatusPolicy.presentation(ChannelState.Loading(groups = groups()))
        )
    }

    @Test
    fun online_content_hides_status_and_snapshot_content_reports_cached_channels() {
        assertNull(TvChannelStatusPolicy.presentation(content(fromSnapshot = false)))
        assertEquals(
            TvChannelStatus(R.string.tv_status_cached, TvChannelStatus.Style.INFO),
            TvChannelStatusPolicy.presentation(content(fromSnapshot = true))
        )
    }

    @Test
    fun empty_and_error_states_distinguish_cached_or_existing_channels() {
        assertEquals(
            TvChannelStatus(R.string.tv_status_empty, TvChannelStatus.Style.EMPTY),
            TvChannelStatusPolicy.presentation(ChannelState.Empty("source", fromSnapshot = false))
        )
        assertEquals(
            TvChannelStatus(R.string.tv_status_empty_cached, TvChannelStatus.Style.EMPTY),
            TvChannelStatusPolicy.presentation(ChannelState.Empty("source", fromSnapshot = true))
        )
        assertEquals(
            TvChannelStatus(R.string.tv_status_error, TvChannelStatus.Style.ERROR),
            TvChannelStatusPolicy.presentation(ChannelState.Error("offline"))
        )
        assertEquals(
            TvChannelStatus(R.string.tv_status_error_with_channels, TvChannelStatus.Style.ERROR),
            TvChannelStatusPolicy.presentation(ChannelState.Error("offline", groups()))
        )
    }

    private fun content(fromSnapshot: Boolean) = ChannelState.Content(
        groups = groups(),
        sourceUrl = "source",
        fromSnapshot = fromSnapshot,
        updatedAtMillis = 0L
    )

    private fun groups() = mapOf("新闻" to listOf(Movie(title = "频道")))
}
