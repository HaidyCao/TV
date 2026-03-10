package com.android.tv

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView

@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_playback)

        val playerView = findViewById<PlayerView>(R.id.player_view)
        player?.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT

        val intent = intent

        val movie = intent.extras?.getSerializable(DetailsActivity.MOVIE) as? Movie
        val videoUrl = movie?.videoUrl

        Log.d("PlaybackActivity", "videoUrl: $videoUrl")

        if (videoUrl.isNullOrEmpty()) {
            Log.e("PlaybackActivity", "Video URL is null or empty!")
            finish()
            return
        }

        // 启用软解码器回退，并优先选使用扩展（Jellyfin FFmpeg）以支持更多的音频编码
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true) // 允许回退到软解码器
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this, renderersFactory)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val stateName = when(state) {
                            1 -> "STATE_IDLE"
                            2 -> "STATE_BUFFERING"
                            3 -> "STATE_READY"
                            4 -> "STATE_ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("PlaybackActivity", "Playback state: $state ($stateName)")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackActivity", "Player error: ${error.message}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("PlaybackActivity", "Is playing: $isPlaying")
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        Log.d("PlaybackActivity", "Tracks changed:")
                        tracks.groups.forEach { group ->
                            val trackType = group.type
                            val mimeType = group.getTrackFormat(0).sampleMimeType
                            Log.d("PlaybackActivity", "  Track type: $trackType, mime: $mimeType, selected: ${group.isSelected}")
                        }
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
