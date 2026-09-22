package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChannelOverlayPolicyTest {

    @Test
    fun automatic_show_is_visible_until_its_deadline() {
        val state = PlaybackChannelOverlayPolicy.autoShown(nowMillis = 1_000L)

        assertTrue(PlaybackChannelOverlayPolicy.isVisibleAt(state, nowMillis = 1_001L))
        assertEquals(
            1_000L + PlaybackChannelOverlayPolicy.AUTO_HIDE_DURATION_MILLIS,
            state.autoHideAtMillis
        )
        assertFalse(
            PlaybackChannelOverlayPolicy.isVisibleAt(
                state,
                nowMillis = state.autoHideAtMillis!!
            )
        )
    }

    @Test
    fun confirm_toggles_visibility_and_manual_show_has_no_deadline() {
        val hidden = PlaybackChannelOverlayPolicy.hidden()
        val shown = PlaybackChannelOverlayPolicy.toggle(hidden, nowMillis = 2_000L)
        val hiddenAgain = PlaybackChannelOverlayPolicy.toggle(shown, nowMillis = 2_001L)

        assertTrue(shown.isVisible)
        assertNull(shown.autoHideAtMillis)
        assertFalse(hiddenAgain.isVisible)
        assertEquals(PlaybackChannelOverlayPolicy.hidden(), hiddenAgain)
    }
}
