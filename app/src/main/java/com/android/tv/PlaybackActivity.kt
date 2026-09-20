package com.android.tv

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView

class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersivePlayback()
        setContentView(R.layout.activity_playback)

        val playerView = findViewById<PlayerView>(R.id.player_view)

        val movie = BundleCompat.getSerializable(
            intent.extras ?: Bundle(),
            DetailsActivity.MOVIE,
            Movie::class.java
        )
        val videoUrl = movie?.videoUrl

        Log.d("PlaybackActivity", "Opening channel: ${movie?.title}")

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
                            Player.STATE_IDLE -> "STATE_IDLE"
                            Player.STATE_BUFFERING -> "STATE_BUFFERING"
                            Player.STATE_READY -> "STATE_READY"
                            Player.STATE_ENDED -> "STATE_ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("PlaybackActivity", "Playback state: $state ($stateName)")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlaybackActivity", "Player error: ${error.message}")
                        Toast.makeText(
                            this@PlaybackActivity,
                            getString(R.string.playback_error),
                            Toast.LENGTH_LONG
                        ).show()
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

                setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
                prepare()
                playWhenReady = true
            }

        playerView.player = player
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersivePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun enableImmersivePlayback() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
