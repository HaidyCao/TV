package com.android.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private data class PlaybackRequest(
        val generation: Long,
        val channelId: Long,
        val sourceUrl: String,
        val videoUrl: String
    )

    private var player: ExoPlayer? = null
    private var currentChannel: Movie? = null
    private var playbackRequestGeneration = 0L
    private var activePlaybackRequest: PlaybackRequest? = null
    private var recordedPlaybackRequestGeneration: Long? = null
    private var playbackUiState = PlaybackUiState.CONNECTING
    private var channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var hideChannelOverlay: Runnable? = null

    private lateinit var playerView: PlayerView
    private lateinit var channelOverlay: View
    private lateinit var channelTitle: TextView
    private lateinit var channelCategory: TextView
    private lateinit var statusOverlay: View
    private lateinit var statusMessage: TextView
    private lateinit var statusActionRow: View
    private lateinit var nextChannelButton: Button
    private lateinit var retryButton: Button
    private lateinit var backButton: Button

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersivePlayback()
        setContentView(R.layout.activity_playback)
        bindViews()
        disablePlayerController()

        nextChannelButton.setOnClickListener {
            switchChannel(direction = PlaybackChannelKeyPolicy.NEXT_CHANNEL)
        }
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
                        updatePlaybackState(state, createdPlayer.isPlaying, createdPlayer)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (playbackUiState != PlaybackUiState.ERROR) {
                        updatePlaybackState(createdPlayer.playbackState, isPlaying, createdPlayer)
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

        playChannel(initialChannel, showOverlay = true)
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
            PlaybackChannelKeyPolicy.directionFor(event.keyCode)?.let { direction ->
                switchChannel(direction)
                return true
            }
            if (isConfirmKey(event.keyCode) &&
                currentChannel != null &&
                playbackUiState != PlaybackUiState.ERROR &&
                playbackUiState != PlaybackUiState.ENDED
            ) {
                toggleChannelOverlay()
                return true
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
        nextChannelButton = findViewById(R.id.playback_next_channel)
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
        playbackRequestGeneration += 1L
        activePlaybackRequest = PlaybackRequest(
            generation = playbackRequestGeneration,
            channelId = channel.id,
            sourceUrl = TvDataManager.getSourceUrl(this),
            videoUrl = videoUrl.toUri().toString()
        )
        recordedPlaybackRequestGeneration = null
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

    private fun updatePlaybackState(playerState: Int, isPlaying: Boolean, callbackPlayer: ExoPlayer) {
        playbackUiState = PlaybackStateMapper.fromPlayerState(playerState, isPlaying)
        renderPlaybackState()
        recordRecentPlaybackIfEligible(callbackPlayer, isPlaying)
    }

    private fun recordRecentPlaybackIfEligible(callbackPlayer: ExoPlayer, isPlaying: Boolean) {
        val request = activePlaybackRequest ?: return
        val channel = currentChannel ?: return
        val repositoryState = ChannelRepository.state.value
        val sourceStillCurrent = request.sourceUrl == TvDataManager.getSourceUrl(this) &&
            ChannelRepository.groupsSourceUrl() == request.sourceUrl
        val channelStillCurrent = channel.id == request.channelId &&
            channel.videoUrl?.toUri()?.toString() == request.videoUrl &&
            callbackPlayer.currentMediaItem?.localConfiguration?.uri?.toString() == request.videoUrl
        val requestStillCurrent = callbackPlayer === player &&
            activePlaybackRequest === request &&
            request.generation == playbackRequestGeneration
        if (!RecentWatchPolicy.shouldRecord(
                playbackState = playbackUiState,
                playerIsPlaying = isPlaying,
                requestStillCurrent = requestStillCurrent,
                channelStillCurrent = channelStillCurrent,
                playlistSourceStillCurrent = sourceStillCurrent
            )
        ) return
        if (recordedPlaybackRequestGeneration == request.generation) return

        val stored = RecentWatchRepository.recordPlayingChannel(
            context = this,
            sourceUrl = request.sourceUrl,
            groupsSourceUrl = ChannelRepository.groupsSourceUrl(),
            groups = repositoryState.groups,
            playingChannel = channel
        )
        if (stored) recordedPlaybackRequestGeneration = request.generation
    }

    private fun showPlaybackError(message: String, canRetry: Boolean) {
        playbackUiState = PlaybackUiState.ERROR
        statusMessage.text = message
        statusOverlay.visibility = View.VISIBLE
        renderStatusActions(canRetry = canRetry, requestFocus = true)
    }

    private fun renderPlaybackState() {
        if (playbackUiState == PlaybackUiState.ERROR) return
        statusOverlay.visibility = when (playbackUiState) {
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED -> View.GONE
            else -> View.VISIBLE
        }
        if (playbackUiState == PlaybackUiState.ENDED) {
            renderStatusActions(canRetry = true, requestFocus = true)
        } else {
            hideStatusActions()
        }
        statusMessage.text = when (playbackUiState) {
            PlaybackUiState.CONNECTING -> getString(R.string.playback_connecting)
            PlaybackUiState.BUFFERING -> getString(R.string.playback_buffering)
            PlaybackUiState.ENDED -> getString(R.string.playback_ended)
            PlaybackUiState.PLAYING,
            PlaybackUiState.PAUSED,
            PlaybackUiState.ERROR -> statusMessage.text
        }
    }

    private fun renderStatusActions(canRetry: Boolean, requestFocus: Boolean) {
        val actions = TvPlaybackActionPolicy.resolve(
            currentChannel = currentChannel,
            channels = ChannelRepository.liveChannels(),
            canRetry = canRetry
        )
        nextChannelButton.visibility = if (actions.hasNextChannel) View.VISIBLE else View.GONE
        retryButton.visibility = if (actions.canRetry) View.VISIBLE else View.GONE
        backButton.visibility = View.VISIBLE
        statusActionRow.visibility = View.VISIBLE
        if (requestFocus) {
            val focusTarget = when (actions.primaryAction) {
                TvPlaybackPrimaryAction.NEXT_CHANNEL -> nextChannelButton
                TvPlaybackPrimaryAction.RETRY -> retryButton
                TvPlaybackPrimaryAction.BACK -> backButton
            }
            focusTarget.post { focusTarget.requestFocus() }
        }
    }

    private fun hideStatusActions() {
        statusActionRow.visibility = View.GONE
        nextChannelButton.visibility = View.GONE
        retryButton.visibility = View.GONE
        backButton.visibility = View.GONE
    }

    private fun disablePlayerController() {
        playerView.useController = false
        playerView.isFocusable = false
        playerView.isFocusableInTouchMode = false
        playerView.clearFocus()
    }

    private fun showChannelOverlay(channel: Movie) {
        channelTitle.text = channel.title
        channelCategory.text = channel.category ?: getString(R.string.live_badge)
        channelOverlayState = PlaybackChannelOverlayPolicy.autoShown(SystemClock.uptimeMillis())
        channelOverlay.visibility = View.VISIBLE
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = Runnable {
            channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
            channelOverlay.visibility = View.GONE
            hideChannelOverlay = null
        }
        overlayHandler.postDelayed(
            hideChannelOverlay!!,
            PlaybackChannelOverlayPolicy.AUTO_HIDE_DURATION_MILLIS
        )
    }

    private fun toggleChannelOverlay() {
        hideChannelOverlay?.let(overlayHandler::removeCallbacks)
        hideChannelOverlay = null
        channelOverlayState = PlaybackChannelOverlayPolicy.toggle(
            state = channelOverlayState,
            nowMillis = SystemClock.uptimeMillis()
        )
        channelOverlay.visibility = if (channelOverlayState.isVisible) View.VISIBLE else View.GONE
        val autoHideAtMillis = channelOverlayState.autoHideAtMillis
        if (autoHideAtMillis != null) {
            val delayMillis = (autoHideAtMillis - SystemClock.uptimeMillis())
                .coerceAtLeast(0L)
            hideChannelOverlay = Runnable {
                channelOverlayState = PlaybackChannelOverlayPolicy.hidden()
                channelOverlay.visibility = View.GONE
                hideChannelOverlay = null
            }
            overlayHandler.postDelayed(hideChannelOverlay!!, delayMillis)
        }
    }

    private fun isConfirmKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
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
    }
}
