package com.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowsePreviewUpdateGatesTest {

    @Test
    fun viewport_scroll_dispatch_is_coalesced_per_frame_and_reset_reopens_gate() {
        val gate = ViewportScrollFrameGate()

        assertTrue(gate.tryDispatch())
        assertFalse(gate.tryDispatch())

        gate.onNextFrame()
        assertTrue(gate.tryDispatch())
        assertFalse(gate.tryDispatch())

        gate.reset()
        assertTrue(gate.tryDispatch())
    }

    @Test
    fun visibility_notifications_track_changes_and_refresh_snapshot_for_new_listener() {
        val gate = VisibilityChangeGate()

        assertTrue(gate.shouldDispatch(false))
        assertFalse(gate.shouldDispatch(false))
        assertTrue(gate.shouldDispatch(true))
        assertTrue(gate.shouldDispatch(false))
        assertTrue(gate.shouldDispatch(true))

        gate.reset()
        assertTrue(gate.shouldDispatch(true))
    }

    @Test
    fun repeated_background_uri_is_skipped_and_old_completion_cannot_become_current_again() {
        val gate = BackgroundUpdateGate()
        val firstNewsRequest = requireNotNull(gate.select("https://example.test/news.jpg"))

        assertNull(gate.select("https://example.test/news.jpg"))
        val sportsRequest = requireNotNull(gate.select("https://example.test/sports.jpg"))
        val secondNewsRequest = requireNotNull(gate.select("https://example.test/news.jpg"))

        assertFalse(gate.isCurrent(firstNewsRequest))
        assertFalse(gate.isCurrent(sportsRequest))
        assertTrue(gate.isCurrent(secondNewsRequest))
    }

    @Test
    fun reset_invalidates_in_flight_background_selection_across_view_recreation() {
        val gate = BackgroundUpdateGate()
        val oldRequest = requireNotNull(gate.select("https://example.test/news.jpg"))

        gate.reset()
        val recreatedViewRequest = requireNotNull(gate.select("https://example.test/news.jpg"))

        assertFalse(gate.isCurrent(oldRequest))
        assertTrue(gate.isCurrent(recreatedViewRequest))
    }
}
