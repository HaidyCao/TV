package com.android.tv

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun idle_and_buffering_are_visible_as_connecting_states() {
        assertEquals(
            PlaybackUiState.CONNECTING,
            PlaybackStateMapper.fromPlayerState(Player.STATE_IDLE, isPlaying = false)
        )
        assertEquals(
            PlaybackUiState.BUFFERING,
            PlaybackStateMapper.fromPlayerState(Player.STATE_BUFFERING, isPlaying = false)
        )
    }

    @Test
    fun ready_state_distinguishes_playing_from_paused() {
        assertEquals(
            PlaybackUiState.PLAYING,
            PlaybackStateMapper.fromPlayerState(Player.STATE_READY, isPlaying = true)
        )
        assertEquals(
            PlaybackUiState.PAUSED,
            PlaybackStateMapper.fromPlayerState(Player.STATE_READY, isPlaying = false)
        )
    }

    @Test
    fun ended_state_is_not_reported_as_playing() {
        assertEquals(
            PlaybackUiState.ENDED,
            PlaybackStateMapper.fromPlayerState(Player.STATE_ENDED, isPlaying = false)
        )
    }

    @Test
    fun player_error_categories_are_safe_for_user_facing_messages() {
        assertEquals(
            PlaybackFailureCategory.NETWORK,
            playbackFailureCategory("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        )
        assertEquals(
            PlaybackFailureCategory.DECODER,
            playbackFailureCategory("ERROR_CODE_DECODING_FAILED")
        )
        assertEquals(
            PlaybackFailureCategory.GENERIC,
            playbackFailureCategory("ERROR_CODE_UNEXPECTED_RUNTIME_ERROR")
        )
    }
}
