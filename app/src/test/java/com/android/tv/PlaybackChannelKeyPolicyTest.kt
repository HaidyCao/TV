package com.android.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackChannelKeyPolicyTest {

    @Test
    fun dpad_up_and_down_follow_channel_direction() {
        assertEquals(
            PlaybackChannelKeyPolicy.PREVIOUS_CHANNEL,
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_UP)
        )
        assertEquals(
            PlaybackChannelKeyPolicy.NEXT_CHANNEL,
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_DOWN)
        )
    }

    @Test
    fun channel_and_page_keys_share_their_dpad_direction() {
        assertEquals(
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_UP),
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_CHANNEL_UP)
        )
        assertEquals(
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_UP),
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_PAGE_UP)
        )
        assertEquals(
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_DOWN),
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_CHANNEL_DOWN)
        )
        assertEquals(
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_DOWN),
            PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_PAGE_DOWN)
        )
    }

    @Test
    fun unrelated_keys_do_not_request_a_channel_switch() {
        assertNull(PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(PlaybackChannelKeyPolicy.directionFor(KeyEvent.KEYCODE_BACK))
    }
}
