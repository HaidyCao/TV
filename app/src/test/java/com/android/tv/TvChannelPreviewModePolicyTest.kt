package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvChannelPreviewModePolicyTest {

    @Test
    fun legacy_boolean_migrates_to_full_or_off_without_changing_phone_preference() {
        assertEquals(
            TvChannelPreviewMode.OFF,
            TvChannelPreviewModePolicy.fromStoredOrLegacy(null, legacyEnabled = false)
        )
        assertEquals(
            TvChannelPreviewMode.FOCUSED_AND_VISIBLE,
            TvChannelPreviewModePolicy.fromStoredOrLegacy(null, legacyEnabled = true)
        )
        assertEquals(
            TvChannelPreviewMode.FOCUSED_AND_VISIBLE,
            TvChannelPreviewModePolicy.fromStoredOrLegacy(null, legacyEnabled = null)
        )
    }

    @Test
    fun explicit_tv_mode_takes_precedence_over_legacy_phone_setting() {
        assertEquals(
            TvChannelPreviewMode.FOCUSED_ONLY,
            TvChannelPreviewModePolicy.fromStoredOrLegacy(
                TvChannelPreviewMode.FOCUSED_ONLY.preferenceValue,
                legacyEnabled = false
            )
        )
    }

    @Test
    fun mode_controls_streams_and_cached_frame_origins() {
        val focused = LivePreviewFrameOrigin.FOCUSED_STREAM
        val static = LivePreviewFrameOrigin.VISIBLE_STATIC_CAPTURE

        assertFalse(TvChannelPreviewModePolicy.allowsFocusedStream(TvChannelPreviewMode.OFF))
        assertFalse(TvChannelPreviewModePolicy.allowsStaticCapture(TvChannelPreviewMode.OFF))
        assertTrue(TvChannelPreviewModePolicy.allowsFocusedStream(TvChannelPreviewMode.FOCUSED_ONLY))
        assertFalse(TvChannelPreviewModePolicy.allowsStaticCapture(TvChannelPreviewMode.FOCUSED_ONLY))
        assertTrue(
            TvChannelPreviewModePolicy.allowsStaticCapture(
                TvChannelPreviewMode.FOCUSED_AND_VISIBLE
            )
        )

        assertFalse(TvChannelPreviewModePolicy.canDisplayFrame(TvChannelPreviewMode.OFF, focused))
        assertFalse(TvChannelPreviewModePolicy.canDisplayFrame(TvChannelPreviewMode.OFF, static))
        assertTrue(
            TvChannelPreviewModePolicy.canDisplayFrame(TvChannelPreviewMode.FOCUSED_ONLY, focused)
        )
        assertFalse(
            TvChannelPreviewModePolicy.canDisplayFrame(TvChannelPreviewMode.FOCUSED_ONLY, static)
        )
        assertTrue(
            TvChannelPreviewModePolicy.canDisplayFrame(
                TvChannelPreviewMode.FOCUSED_AND_VISIBLE,
                static
            )
        )
    }
}

class VisiblePreviewSettlePolicyTest {

    @Test
    fun repeated_visibility_notifications_do_not_move_initial_settle_deadline() {
        val initialDeadline = VisiblePreviewSettlePolicy.initialDeadline(
            existingDeadlineMs = null,
            nowMs = 100L,
            quietPeriodMs = 180L
        )

        assertEquals(280L, initialDeadline)
        assertEquals(
            initialDeadline,
            VisiblePreviewSettlePolicy.initialDeadline(
                existingDeadlineMs = initialDeadline,
                nowMs = 250L,
                quietPeriodMs = 180L
            )
        )
    }

    @Test
    fun an_actual_viewport_scroll_extends_quiet_period_and_expires_cleanly() {
        val afterScroll = VisiblePreviewSettlePolicy.scrollDeadline(250L, 300L)

        assertEquals(550L, afterScroll)
        assertEquals(100L, VisiblePreviewSettlePolicy.remainingDelay(450L, afterScroll))
        assertEquals(0L, VisiblePreviewSettlePolicy.remainingDelay(550L, afterScroll))
        assertEquals(0L, VisiblePreviewSettlePolicy.remainingDelay(600L, null))
    }

    @Test
    fun focus_release_adds_a_short_window_and_preserves_a_longer_scroll_window() {
        assertEquals(
            480L,
            VisiblePreviewSettlePolicy.focusReleaseDeadline(
                nowMs = 300L,
                existingDeadlineMs = null,
                quietPeriodMs = 180L
            )
        )
        assertEquals(
            550L,
            VisiblePreviewSettlePolicy.focusReleaseDeadline(
                nowMs = 300L,
                existingDeadlineMs = 550L,
                quietPeriodMs = 180L
            )
        )
    }
}

class PreviewTelemetryTrackerTest {

    @Test
    fun counts_focus_static_latency_scroll_periods_and_peak_concurrency() {
        val telemetry = PreviewTelemetryTracker()
        val focusToken = telemetry.started(PreviewStreamKind.FOCUSED, nowMs = 100L)
        val staticToken = telemetry.started(
            PreviewStreamKind.STATIC,
            nowMs = 140L,
            duringScroll = true
        )

        assertEquals(2, telemetry.snapshot().activePlayers)
        assertEquals(2, telemetry.snapshot().peakConcurrentPlayers)
        assertEquals(1, telemetry.snapshot().staticStartsDuringScroll)
        assertEquals(90L, telemetry.firstFrame(focusToken, nowMs = 190L)?.latencyMs)
        assertEquals(null, telemetry.firstFrame(focusToken, nowMs = 200L))
        assertEquals(120L, telemetry.firstFrame(staticToken, nowMs = 260L)?.latencyMs)

        telemetry.stopped(focusToken)
        telemetry.stopped(staticToken)
        val snapshot = telemetry.snapshot()
        assertEquals(1, snapshot.focusedStarts)
        assertEquals(1, snapshot.staticStarts)
        assertEquals(1, snapshot.focusedFirstFrames)
        assertEquals(1, snapshot.staticFirstFrames)
        assertEquals(0, snapshot.activePlayers)
        assertEquals(2, snapshot.peakConcurrentPlayers)
    }
}
