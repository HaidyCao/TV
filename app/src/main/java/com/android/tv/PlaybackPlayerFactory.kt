package com.android.tv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/** Creates the single player configuration shared by the TV and phone playback screens. */
@UnstableApi
internal object PlaybackPlayerFactory {
    fun create(context: Context): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        return ExoPlayer.Builder(context, renderersFactory).build()
    }
}
