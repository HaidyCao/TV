package com.android.tv

import androidx.media3.common.Player
import androidx.media3.common.PlaybackException

internal enum class PlaybackUiState {
    CONNECTING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

internal enum class PlaybackFailureCategory {
    NETWORK,
    DECODER,
    SOURCE,
    GENERIC
}

internal object PlaybackStateMapper {
    fun fromPlayerState(playerState: Int, isPlaying: Boolean): PlaybackUiState {
        return when (playerState) {
            Player.STATE_BUFFERING -> PlaybackUiState.BUFFERING
            Player.STATE_READY -> if (isPlaying) PlaybackUiState.PLAYING else PlaybackUiState.PAUSED
            Player.STATE_ENDED -> PlaybackUiState.ENDED
            Player.STATE_IDLE -> PlaybackUiState.CONNECTING
            else -> PlaybackUiState.CONNECTING
        }
    }
}

internal fun playbackFailureCategory(error: PlaybackException): PlaybackFailureCategory {
    return playbackFailureCategory(error.errorCodeName)
}

internal fun playbackFailureCategory(errorCodeName: String): PlaybackFailureCategory {
    return when {
        errorCodeName.contains("NETWORK") ||
            errorCodeName.contains("HTTP") ||
            errorCodeName.contains("TIMEOUT") ||
            errorCodeName.contains("CONNECTION") -> PlaybackFailureCategory.NETWORK
        errorCodeName.contains("DECOD") || errorCodeName.contains("FORMAT") -> {
            PlaybackFailureCategory.DECODER
        }
        errorCodeName.contains("SOURCE") || errorCodeName.contains("MANIFEST") -> {
            PlaybackFailureCategory.SOURCE
        }
        else -> PlaybackFailureCategory.GENERIC
    }
}

internal fun PlaybackFailureCategory.messageResId(): Int {
    return when (this) {
        PlaybackFailureCategory.NETWORK -> R.string.playback_failed_network
        PlaybackFailureCategory.DECODER -> R.string.playback_failed_decoder
        PlaybackFailureCategory.SOURCE -> R.string.playback_failed_source
        PlaybackFailureCategory.GENERIC -> R.string.playback_failed_generic
    }
}
