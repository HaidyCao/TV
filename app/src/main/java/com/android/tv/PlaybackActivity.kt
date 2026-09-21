package com.android.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
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
import androidx.media3.ui.PlayerView

class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var currentChannel: Movie? = null
    private var playbackUiState = PlaybackUiState.CONNECTING
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var hideChannelOverlay: Runnable? = null

    private lateinit var playerView: PlayerView
    private lateinit var channelOverlay: View
    private lateinit var channelTitle: TextView
    private lateinit var channelCategory: TextView
    private lateinit var statusOverlay: View
    private lateinit var statusMessage: TextView
    private lateinit var statusActionRow: View
    private lateinit var retryButton: Button
    private lateinit var backButton: Button

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersivePlayback()
        setContentView(R.layout.activity_playback)
        bindViews()

        retryButton.setOnClickListener { retryCurrentChannel() }
        backButton.setOnClickListener { finish() }
        renderPlaybackState()

        val initialChannel = BundleCompat.getSerializable(
            intent.extras ?: Bundle(),
            DetailsActivity.MOVIE,
            Movie::class.java
        )
        if (initialChannel == null) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }

        val videoUrl = initialChannel.videoUrl
        Log.d(TAG, "Opening channel: ${initialChannel.title}")
        if (videoUrl.isNullOrBlank()) {
            Log.e(TAG, "Video URL is null or empty")
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        currentChannel = initialChannel

        player = PlaybackPlayerFactory.create(this).also { createdPlayer ->
            createdPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (playbackUiState != PlaybackUiState.ERROR) {
                        updatePlaybackState(state, createdPlayer.isPlaying)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (playbackUiState != PlaybackUiState.ERROR) {
                        updatePlaybackState(createdPlayer.playbackState, isPlaying)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.errorCodeName} (${error.message})")
                    showPlaybackError(
                        getString(playbackFailureCategory(error).messageResId()),
                        canRetry = true
                    )
                }
            })
            playerView.player = createdPlayer
        }

        playChannel(initialChannel)
        ChannelRepository.ensureLoaded(this)
    }

    override fun onResume() {
        super.onResume()
        enableImmersivePlayback()
        if (playbackUiState != PlaybackUiState.ERROR && currentChannel != null) {
            player?.play()
        }
    }

    override fun onPause() {
        player?.pause()
        super.onPause()
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
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        channelOverlay = findViewById(R.id.channel_switch_overlay)
        channelTitle = findViewById(R.id.channel_switch_title)
        channelCategory = findViewById(R.id.channel_switch_category)
        statusOverlay = findViewById(R.id.playback_status_overlay)
        statusMessage = findViewById(R.id.playback_status_message)
        statusActionRow = findViewById(R.id.playback_action_row)
        retryButton = findViewById(R.id.playback_retry)
        backButton = findViewById(R.id.playback_back)
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

    private fun retryCurrentChannel() {
        val channel = currentChannel
        if (channel == null || channel.videoUrl.isNullOrBlank()) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        playChannel(channel)
    }

    private fun playChannel(channel: Movie, showOverlay: Boolean = false) {
        val videoUrl = channel.videoUrl
        if (videoUrl.isNullOrBlank()) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        currentChannel = channel
        playbackUiState = PlaybackUiState.CONNECTING
        renderPlaybackState()
        player?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
            prepare()
            playWhenReady = true
        }
        if (showOverlay) showChannelOverlay(channel)
    }

    private fun updatePlaybackState(playerState: Int, isPlaying: Boolean) {
        playbackUiState = PlaybackStateMapper.fromPlayerState(playerState, isPlaying)
        renderPlaybackState()
    }

    private fun showPlaybackError(message: String, canRetry: Boolean) {
        playbackUiState = PlaybackUiState.ERROR
        setPlayerControllerEnabled(false)
        statusMessage.text = message
        statusOverlay.visibility = View.VISIBLE
        statusActionRow.visibility = View.VISIBLE
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        backButton.visibility = View.VISIBLE
        val focusTarget = if (canRetry) retryButton else backButton
        focusTarget.post { focusTarget.requestFocus() }
    }

    private fun renderPlaybackState() {
        if (playbackUiState == PlaybackUiState.ERROR) return
        setPlayerControllerEnabled(playbackUiState != PlaybackUiState.ENDED)
        statusOverlay.visibility = when (playbackUiState) {
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED -> View.GONE
            else -> View.VISIBLE
        }
        val canRetry = playbackUiState == PlaybackUiState.ENDED
        statusActionRow.visibility = if (canRetry) View.VISIBLE else View.GONE
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        backButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        if (canRetry) retryButton.post { retryButton.requestFocus() }
        statusMessage.text = when (playbackUiState) {
            PlaybackUiState.CONNECTING -> getString(R.string.playback_connecting)
            PlaybackUiState.BUFFERING -> getString(R.string.playback_buffering)
            PlaybackUiState.ENDED -> getString(R.string.playback_ended)
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED,
            PlaybackUiState.ERROR -> statusMessage.text
        }
    }

    private fun setPlayerControllerEnabled(enabled: Boolean) {
        playerView.useController = enabled
        playerView.isFocusable = enabled
        playerView.isFocusableInTouchMode = enabled
        if (!enabled) {
            playerView.clearFocus()
        }
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
        const val TAG = "PlaybackActivity"
        const val CHANNEL_OVERLAY_DURATION_MS = 2_500L
    }
}
