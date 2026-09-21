package com.android.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
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
    private var currentChannel: Movie? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var hideChannelOverlay: Runnable? = null

    private lateinit var playerView: PlayerView
    private lateinit var channelOverlay: View
    private lateinit var channelTitle: TextView
    private lateinit var channelCategory: TextView

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersivePlayback()
        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.player_view)
        channelOverlay = findViewById(R.id.channel_switch_overlay)
        channelTitle = findViewById(R.id.channel_switch_title)
        channelCategory = findViewById(R.id.channel_switch_category)

        val movie = BundleCompat.getSerializable(
            intent.extras ?: Bundle(),
            DetailsActivity.MOVIE,
            Movie::class.java
        )
        val initialChannel = movie ?: run {
            finish()
            return
        }
        val videoUrl = initialChannel.videoUrl

        Log.d("PlaybackActivity", "Opening channel: ${initialChannel.title}")

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

            }

        playerView.player = player
        playChannel(initialChannel)
        ChannelRepository.ensureLoaded(this)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersivePlayback()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    switchChannel(direction = -1)
                    return true
                }

                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    switchChannel(direction = 1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun switchChannel(direction: Int) {
        val current = currentChannel ?: return
        val nextChannel = ChannelPlaybackNavigator.adjacent(
            current = current,
            channels = ChannelRepository.liveChannels(),
            direction = direction
        )
        if (nextChannel == null) {
            Toast.makeText(this, R.string.channel_switch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        playChannel(nextChannel, showOverlay = true)
    }

    private fun playChannel(channel: Movie, showOverlay: Boolean = false) {
        val videoUrl = channel.videoUrl ?: return
        currentChannel = channel
        player?.apply {
            setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
            prepare()
            play()
        }
        if (showOverlay) showChannelOverlay(channel)
    }

    private fun showChannelOverlay(channel: Movie) {
        channelTitle.text = channel.title
        channelCategory.text = channel.category ?: getString(R.string.live_badge)
        channelOverlay.visibility = View.VISIBLE
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = Runnable { channelOverlay.visibility = View.GONE }
        overlayHandler.postDelayed(hideChannelOverlay!!, CHANNEL_OVERLAY_DURATION_MS)
    }

    private fun enableImmersivePlayback() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val CHANNEL_OVERLAY_DURATION_MS = 2_500L
    }
}
