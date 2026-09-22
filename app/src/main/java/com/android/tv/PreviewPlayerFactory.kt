package com.android.tv

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Creates the player used by browse-card previews.
 *
 * Previews only need video. Keeping the extension renderers out of this path
 * avoids loading the FFmpeg decoder for a muted, short-lived player while the
 * full playback screens continue to use [PlaybackPlayerFactory].
 */
@SuppressLint("UnsafeOptInUsageError")
internal object PreviewPlayerFactory {
    fun create(context: Context, lowResolution: Boolean = false): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val trackSelector = DefaultTrackSelector(context)
        val trackParameters = trackSelector.buildUponParameters()
        if (lowResolution) {
            trackParameters.setMaxVideoSize(STATIC_MAX_VIDEO_WIDTH, STATIC_MAX_VIDEO_HEIGHT)
            trackParameters.setForceLowestBitrate(true)
        } else {
            trackParameters.setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
        }
        // Static captures prefer 360p, but must still work when a stream only
        // advertises 720p/1080p variants. Lowest bitrate remains enabled, so
        // this fallback does not opt into a higher-quality representation.
        trackParameters.setExceedVideoConstraintsIfNecessary(lowResolution)
        trackParameters.setExceedRendererCapabilitiesIfNecessary(false)
        trackParameters.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        trackSelector.setParameters(trackParameters)

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { player -> player.volume = 0f }
    }

    private const val MAX_VIDEO_WIDTH = 1920
    private const val MAX_VIDEO_HEIGHT = 1080
    private const val STATIC_MAX_VIDEO_WIDTH = 640
    private const val STATIC_MAX_VIDEO_HEIGHT = 360
}
