package com.android.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Owns the one live preview used by the TV browse screen.
 *
 * Selection is delayed so moving across a row does not open every stream. A
 * generation token makes a delayed callback harmless after focus moves or the
 * view is replaced.
 */
@SuppressLint("UnsafeOptInUsageError")
internal class TvChannelPreviewController(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val frameStore: LivePreviewFrameStore = LivePreviewFrameStore()
) {
    private val appContext = context.applicationContext
    private val generation = TvChannelPreviewGeneration()

    private var pendingTask: Runnable? = null
    private var pendingHolder: CardPresenter.CardViewHolder? = null
    private var player: ExoPlayer? = null
    private var activeListener: Player.Listener? = null
    private var activeHolder: CardPresenter.CardViewHolder? = null
    private var activeTextureView: android.view.TextureView? = null
    private var activeMovieKey: String? = null
    private var activeVideoUrl: String? = null
    private var hasRenderedFirstFrame = false
    private var activeToken: Long = 0L

    /** Schedules a muted preview for the currently focused live card. */
    fun schedule(movie: Movie, holder: CardPresenter.CardViewHolder) {
        stop()
        if (!TvChannelPreviewPolicy.canPreview(movie)) return

        val token = generation.current()
        pendingHolder = holder
        Log.d(TAG, "schedule ${logLabel(movie)}")
        pendingTask = Runnable {
            pendingTask = null
            pendingHolder = null
            if (!generation.isCurrent(token) || !isValidSelection(movie, holder)) {
                if (generation.isCurrent(token)) stop()
                return@Runnable
            }
            startPreview(token, movie, holder)
        }
        handler.postDelayed(pendingTask!!, TvChannelPreviewPolicy.FOCUS_DELAY_MS)
    }

    /** Stops any pending or active preview immediately. */
    fun stop() {
        generation.next()
        pendingTask?.let(handler::removeCallbacks)
        pendingTask = null
        pendingHolder = null

        val oldPlayer = player
        // Clear the field before touching ExoPlayer. Releasing can synchronously
        // dispatch callbacks; a re-entrant stop must not release the same player
        // twice or mistake it for a newly scheduled preview.
        player = null
        val oldListener = activeListener
        val oldHolder = activeHolder
        val oldTexture = activeTextureView
        val oldMovieKey = activeMovieKey
        val oldVideoUrl = activeVideoUrl
        val oldHasRenderedFirstFrame = hasRenderedFirstFrame

        // TextureView.getBitmap() must run before the player detaches or releases
        // the surface. Only a confirmed first frame from the still-bound holder
        // is eligible for the shared cache.
        val lastFrame = if (oldHasRenderedFirstFrame && oldHolder != null &&
            oldTexture != null && oldMovieKey != null && oldVideoUrl != null &&
            oldHolder.isBoundTo(oldMovieKey)
        ) {
            captureRenderedFrame(oldTexture)
        } else {
            null
        }

        if (oldPlayer != null && oldListener != null) {
            oldPlayer.removeListener(oldListener)
        }
        activeListener = null
        activeHolder = null
        activeTextureView = null
        activeMovieKey = null
        activeVideoUrl = null
        hasRenderedFirstFrame = false
        activeToken = 0L

        if (oldPlayer != null && oldTexture != null) {
            runCatching { oldPlayer.clearVideoTextureView(oldTexture) }
        }
        if (oldHolder != null && oldMovieKey != null) {
            if (lastFrame != null) {
                // The active card keeps its frame even if the bitmap is too
                // large for the shared cache; the cache only serves future
                // bindings of this URL.
                oldVideoUrl?.let { frameStore.put(it, lastFrame) }
                oldHolder.showCapturedPreviewFrame(oldMovieKey, lastFrame)
            } else {
                // Keep the existing logo/card image when the surface did not
                // produce a valid frame or the holder was rebound meanwhile.
                oldHolder.resetPreviewLayerIfBound(oldMovieKey)
            }
        }

        oldPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.clearMediaItems() }
            // Keep release independent from stop/clear: a broken codec state
            // must not prevent the native player from being disposed.
            runCatching { it.release() }
        }
        if (oldHolder != null || oldTexture != null) {
            Log.d(TAG, "stop")
        }
    }

    /** Stops and releases the player when the browse view is destroyed. */
    fun release() {
        stop()
    }

    /** Called by CardPresenter before a holder is rebound or recycled. */
    fun stopIfAttached(holder: CardPresenter.CardViewHolder) {
        if (holder === activeHolder || holder === pendingHolder) stop()
    }

    private fun isValidSelection(
        movie: Movie,
        holder: CardPresenter.CardViewHolder
    ): Boolean {
        return TvChannelPreviewPolicy.canPreview(movie) &&
            holder.cardView.isAttachedToWindow &&
            holder.cardView.hasFocus()
    }

    private fun startPreview(
        token: Long,
        movie: Movie,
        holder: CardPresenter.CardViewHolder
    ) {
        if (!generation.isCurrent(token) || !isValidSelection(movie, holder)) return
        val videoUrl = movie.videoUrl ?: return
        val textureView = holder.ensurePreviewTexture()
        // Mark the surface before creating the player so a factory/setup
        // failure also resets the holder and removes the just-created texture.
        activeHolder = holder
        activeTextureView = textureView
        activeMovieKey = previewKey(movie)
        activeVideoUrl = videoUrl
        hasRenderedFirstFrame = false
        activeToken = token
        val previewPlayer = player ?: try {
            PreviewPlayerFactory.create(appContext).also { createdPlayer ->
                createdPlayer.repeatMode = Player.REPEAT_MODE_OFF
                player = createdPlayer
            }
        } catch (error: Exception) {
            Log.w(TAG, "error setup", error)
            stop()
            return
        }
        previewPlayer.volume = 0f

        activeListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player === previewPlayer && activeToken == token && activeHolder === holder &&
                    generation.isCurrent(token)
                ) {
                    Log.d(TAG, "first-frame ${logLabel(movie)}")
                    hasRenderedFirstFrame = true
                    holder.showPreviewFrame()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player === previewPlayer && activeToken == token && activeHolder === holder &&
                    generation.isCurrent(token)
                ) {
                    Log.w(TAG, "error ${error.errorCodeName}")
                    stop()
                }
            }
        }.also(previewPlayer::addListener)
        Log.d(TAG, "start ${logLabel(movie)}")
        runCatching {
            previewPlayer.setVideoTextureView(textureView)
            previewPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
            previewPlayer.prepare()
            previewPlayer.playWhenReady = true
        }.onFailure {
            Log.w(TAG, "error setup")
            stop()
        }
    }

    private fun logLabel(movie: Movie): String {
        return movie.title.orEmpty().take(40).ifBlank { "channel" }
    }

    private fun previewKey(movie: Movie): String {
        return "${movie.id}:${movie.videoUrl.orEmpty()}"
    }

    private fun captureRenderedFrame(textureView: android.view.TextureView): Bitmap? {
        return runCatching { textureView.bitmap }
            .getOrNull()
            ?.takeIf { bitmap ->
                !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
            }
    }

    companion object {
        private const val TAG = "TvChannelPreview"
    }
}

internal object TvChannelPreviewPolicy {
    const val FOCUS_DELAY_MS = 800L

    fun canPreview(movie: Movie): Boolean {
        return movie.isLive && !movie.videoUrl.isNullOrBlank()
    }
}

internal class TvChannelPreviewGeneration {
    private var value = 0L

    fun next(): Long {
        value += 1L
        return value
    }

    fun current(): Long = value

    fun isCurrent(token: Long): Boolean = token == value
}
