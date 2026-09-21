package com.android.tv

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
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

class PhonePlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var currentChannel: Movie? = null
    private var playbackUiState = PlaybackUiState.CONNECTING

    private lateinit var playerView: PlayerView
    private lateinit var statusOverlay: View
    private lateinit var statusMessage: TextView
    private lateinit var statusActionRow: View
    private lateinit var retryButton: Button
    private lateinit var backButton: Button

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_playback)
        bindViews()
        enableImmersivePlayback()

        retryButton.setOnClickListener { retryCurrentChannel() }
        backButton.setOnClickListener { finish() }
        renderPlaybackState()

        val initialChannel = BundleCompat.getSerializable(
            intent.extras ?: Bundle(),
            EXTRA_CHANNEL,
            Movie::class.java
        )
        if (initialChannel == null) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }

        val videoUrl = initialChannel.videoUrl
        Log.d(TAG, "Playing: ${initialChannel.title}")
        if (videoUrl.isNullOrBlank()) {
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
            createdPlayer.setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
            playerView.player = createdPlayer
        }
        player?.prepare()
        player?.playWhenReady = true
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

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player_view)
        statusOverlay = findViewById(R.id.playback_status_overlay)
        statusMessage = findViewById(R.id.playback_status_message)
        statusActionRow = findViewById(R.id.playback_action_row)
        retryButton = findViewById(R.id.playback_retry)
        backButton = findViewById(R.id.playback_back)
    }

    private fun retryCurrentChannel() {
        val channel = currentChannel
        val videoUrl = channel?.videoUrl
        if (videoUrl.isNullOrBlank()) {
            showPlaybackError(getString(R.string.playback_invalid_channel), canRetry = false)
            return
        }
        playbackUiState = PlaybackUiState.CONNECTING
        renderPlaybackState()
        player?.apply {
            stop()
            setMediaItem(MediaItem.fromUri(videoUrl.toUri()))
            prepare()
            playWhenReady = true
        }
    }

    private fun updatePlaybackState(playerState: Int, isPlaying: Boolean) {
        playbackUiState = PlaybackStateMapper.fromPlayerState(playerState, isPlaying)
        renderPlaybackState()
    }

    private fun showPlaybackError(message: String, canRetry: Boolean) {
        playbackUiState = PlaybackUiState.ERROR
        statusMessage.text = message
        statusOverlay.visibility = View.VISIBLE
        statusActionRow.visibility = View.VISIBLE
        retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        backButton.visibility = View.VISIBLE
    }

    private fun renderPlaybackState() {
        if (playbackUiState == PlaybackUiState.ERROR) return
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

    private fun enableImmersivePlayback() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val EXTRA_CHANNEL = "channel"
        const val TAG = "PhonePlayback"
    }
}
