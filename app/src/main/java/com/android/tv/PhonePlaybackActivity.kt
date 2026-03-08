package com.android.tv

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView

@UnstableApi
class PhonePlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val movie = intent.getSerializableExtra("channel") as? Movie
        val videoUrl = movie?.videoUrl

        Log.d("PhonePlayback", "Playing: ${movie?.title}, URL: $videoUrl")

        if (videoUrl.isNullOrEmpty()) {
            finish()
            return
        }

        // 全屏播放
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        val playerView = PlayerView(this)
        playerView.setBackgroundColor(android.graphics.Color.BLACK)
        playerView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(playerView)

        // 启用软解码器回退，并优先使用扩展（Jellyfin FFmpeg）以支持 MP2/AC3 等格式
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    Log.d("PhonePlayback", "Tracks changed:")
                    tracks.groups.forEach { group ->
                        val format = group.getTrackFormat(0)
                        val type = group.type // 1: Audio, 2: Video
                        val mime = format.sampleMimeType
                        val isSupported = group.isTrackSupported(0)
                        val isSelected = group.isSelected
                        Log.d("PhonePlayback", "  - Type: $type, Mime: $mime, Supported: $isSupported, Selected: $isSelected")
                        
                        if (type == androidx.media3.common.C.TRACK_TYPE_AUDIO && isSelected && !isSupported) {
                            Log.e("PhonePlayback", "Audio track selected but NOT SUPPORTED by device!")
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PhonePlayback", "Player Error: ${error.errorCodeName} (${error.errorCode}), message: ${error.message}")
                    if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
                        Log.e("PhonePlayback", "Decoding failed - likely missing codec")
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.d("PhonePlayback", "State changed: $state")
                }
            })

            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            prepare()
            playWhenReady = true
        }
        
        playerView.player = player
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
