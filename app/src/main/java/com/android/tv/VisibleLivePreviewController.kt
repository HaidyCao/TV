package com.android.tv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Captures one low-resolution frame at a time for live cards that are visible
 * but not focused. The focused card remains owned by TvChannelPreviewController.
 */
@SuppressLint("UnsafeOptInUsageError")
internal class VisibleLivePreviewController(
    context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val frameStore: LivePreviewFrameStore
) {
    private val appContext = context.applicationContext
    private val requestQueue = VisibleLivePreviewRequestQueue()
    private val targets = LinkedHashMap<CardPresenter.CardViewHolder, Target>()
    private val failedUrls = mutableSetOf<String>()

    // MainFragment resumes this after the browse view reaches RESUMED.
    private var paused = true
    private var cleaningUp = false
    private var generation = 0L
    private var active: Active? = null
    private var timeoutTask: Runnable? = null
    private var settleTask: Runnable? = null
    private var settleDeadlineMs: Long? = null
    private var settledSinceViewportChange = false
    private var scrollQuietUntilMs = 0L
    private var focusPriorityPending = false

    /** Registers or refreshes one visible holder. Requests are de-duplicated by URL. */
    fun request(movie: Movie, holder: CardPresenter.CardViewHolder) {
        if (cleaningUp) return
        val videoUrl = movie.videoUrl.orEmpty()
        val requestKey = previewKey(movie)
        val candidate = VisibleLivePreviewCandidate(
            requestKey = requestKey,
            videoUrl = videoUrl,
            isLive = movie.isLive,
            isVisible = holder.isActuallyVisible(),
            isFocused = holder.isFocused()
        )
        if (!holder.isBoundTo(requestKey) ||
            !VisibleLivePreviewPolicy.canRequest(candidate, frameStore.get(videoUrl) != null)
        ) {
            cancel(holder)
            return
        }

        val previous = targets.put(holder, Target(movie, requestKey, videoUrl, holder))
        if (previous != null && previous.videoUrl != videoUrl) {
            removeQueuedUrlIfUnused(previous.videoUrl)
            failedUrls.remove(previous.videoUrl)
        }
        if (!paused && !failedUrls.contains(videoUrl)) {
            requestQueue.enqueue(videoUrl)
        }
        pump()
    }

    /** Removes a holder from both the pending set and the active capture. */
    fun cancel(holder: CardPresenter.CardViewHolder) {
        if (cleaningUp) return
        val wasActive = active?.target?.holder === holder
        val previous = targets.remove(holder)
        if (previous != null) removeQueuedUrlIfUnused(previous.videoUrl)
        if (wasActive) {
            cancelActive(resetHolder = true)
        }
        pump()
    }

    /** Stops I/O while retaining bound holders for a later resume. */
    fun pause() {
        paused = true
        requestQueue.clear()
        cancelSettleTask()
        settleDeadlineMs = null
        settledSinceViewportChange = false
        scrollQuietUntilMs = 0L
        cancelActive(resetHolder = true)
    }

    /** Rechecks retained holders after the browse fragment resumes. */
    fun resume() {
        paused = false
        pump()
    }

    /** Gives the delayed focused stream the first connection opportunity. */
    fun setFocusPriorityPending(pending: Boolean) {
        val wasPending = focusPriorityPending
        focusPriorityPending = pending
        if (pending) {
            cancelActive(resetHolder = true)
        } else if (!paused) {
            settledSinceViewportChange = false
            settleDeadlineMs = VisiblePreviewSettlePolicy.focusReleaseDeadline(
                SystemClock.uptimeMillis(),
                settleDeadlineMs,
                FOCUS_RELEASE_SETTLE_MS
            )
            cancelSettleTask()
            scheduleSettleTask()
        } else if (wasPending) {
            settledSinceViewportChange = false
            settleDeadlineMs = null
            cancelSettleTask()
        }
    }

    /** Called only by real scroll callbacks, never by layout/visibility changes. */
    fun onViewportScrolled() {
        if (paused || cleaningUp) return
        settledSinceViewportChange = false
        scrollQuietUntilMs = VisiblePreviewSettlePolicy.scrollDeadline(
            SystemClock.uptimeMillis(),
            VIEWPORT_QUIET_PERIOD_MS
        )
        settleDeadlineMs = scrollQuietUntilMs
        cancelSettleTask()
        cancelActive(resetHolder = true)
        scheduleSettleTask()
    }

    /** Stops I/O and forgets all holders when rows are replaced. */
    fun reset() {
        pause()
        targets.clear()
        failedUrls.clear()
        requestQueue.clear()
        focusPriorityPending = false
        settleDeadlineMs = null
        scrollQuietUntilMs = 0L
    }

    /** Releases the controller with no retained callbacks or holder references. */
    fun release() {
        reset()
        cancelSettleTask()
        timeoutTask?.let(handler::removeCallbacks)
        timeoutTask = null
    }

    private fun pump() {
        if (paused || active != null || focusPriorityPending) return

        pruneInvalidTargets()
        enqueueEligibleUrls()

        if (targets.values.none(::isEligible)) {
            if (targets.isEmpty()) {
                settledSinceViewportChange = false
                settleDeadlineMs = null
                cancelSettleTask()
            }
            return
        }

        if (!settledSinceViewportChange) {
            settleDeadlineMs = VisiblePreviewSettlePolicy.initialDeadline(
                settleDeadlineMs,
                SystemClock.uptimeMillis(),
                INITIAL_SETTLE_DELAY_MS
            )
            scheduleSettleTask()
            return
        }

        while (true) {
            val request = requestQueue.pollSkipping(failedUrls) ?: return
            if (frameStore.get(request.videoUrl) != null) {
                removeTargetsForUrl(request.videoUrl)
                continue
            }
            val target = targets.values.firstOrNull { target ->
                target.videoUrl == request.videoUrl && isEligible(target)
            }
            if (target == null) continue
            // Measure against the viewport's own quiet deadline, independently
            // of the scheduler state that normally prevents such starts.
            val duringScroll = SystemClock.uptimeMillis() < scrollQuietUntilMs
            start(target, duringScroll)
            return
        }
    }

    private fun pruneInvalidTargets() {
        targets.values.toList().forEach { target ->
            // Keep a failed target registered until its holder is explicitly
            // unbound or hidden. This prevents resetPreviewLayer() visibility
            // callbacks from immediately retrying a failed URL forever.
            if (failedUrls.contains(target.videoUrl)) return@forEach
            if (!isEligible(target) && active?.target?.holder !== target.holder) {
                targets.remove(target.holder)
                removeQueuedUrlIfUnused(target.videoUrl)
            }
        }
    }

    private fun enqueueEligibleUrls() {
        targets.values
            .filter(::isEligible)
            .map(Target::videoUrl)
            .distinct()
            .filterNot(failedUrls::contains)
            .forEach(requestQueue::enqueue)
    }

    private fun isEligible(target: Target): Boolean {
        if (!target.holder.isBoundTo(target.requestKey)) return false
        return VisibleLivePreviewPolicy.canRequest(
            VisibleLivePreviewCandidate(
                requestKey = target.requestKey,
                videoUrl = target.videoUrl,
                isLive = target.movie.isLive,
                isVisible = target.holder.isActuallyVisible(),
                isFocused = target.holder.isFocused()
            ),
            cached = frameStore.get(target.videoUrl) != null
        ) && !failedUrls.contains(target.videoUrl)
    }

    private fun start(target: Target, duringScroll: Boolean) {
        if (!isEligible(target)) {
            pump()
            return
        }

        val textureView = try {
            target.holder.ensurePreviewTexture()
        } catch (error: Exception) {
            Log.w(TAG, "unable to create static preview surface", error)
            fail(target, resetHolder = false)
            return
        }

        val player = try {
            PreviewPlayerFactory.create(appContext, lowResolution = true).also {
                it.repeatMode = Player.REPEAT_MODE_OFF
                it.volume = 0f
            }
        } catch (error: Exception) {
            Log.w(TAG, "unable to create static preview player", error)
            target.holder.resetPreviewLayerIfBound(target.requestKey)
            fail(target, resetHolder = false)
            return
        }

        // Creating the low-resolution player can take long enough for a row
        // to scroll or lose focus. Recheck before opening the network source.
        if (!isEligible(target)) {
            runCatching { player.release() }
            target.holder.resetPreviewLayerIfBound(target.requestKey)
            pump()
            return
        }

        val token = ++generation
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // Let TextureView commit the rendered buffer before asking it
                // for a bitmap. A later callback is harmless if focus moved.
                handler.post {
                    val current = active
                    if (current == null || current.token != token || current.player !== player) return@post
                    if (!isEligible(target)) {
                        cancelActive(resetHolder = true)
                        pump()
                        return@post
                    }
                    PreviewStreamTelemetry.firstFrame(current.telemetryToken)
                    val bitmap = captureRenderedFrame(textureView)
                    if (bitmap == null) {
                        fail(target, resetHolder = true)
                    } else {
                        succeed(target, bitmap)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = active
                if (current?.token == token && current.player === player) {
                    Log.w(TAG, "static preview error ${error.errorCodeName}")
                    fail(target, resetHolder = true)
                }
            }
        }

        val telemetryToken = PreviewStreamTelemetry.started(
            PreviewStreamKind.STATIC,
            duringScroll = duringScroll
        )
        active = Active(token, target, player, textureView, listener, telemetryToken)
        player.addListener(listener)
        timeoutTask = Runnable {
            val current = active
            if (current?.token == token && current.player === player) {
                Log.w(TAG, "static preview timed out")
                fail(target, resetHolder = true)
            }
        }.also { handler.postDelayed(it, STATIC_CAPTURE_TIMEOUT_MS) }

        try {
            player.setVideoTextureView(textureView)
            player.setMediaItem(MediaItem.fromUri(target.videoUrl))
            player.prepare()
            player.playWhenReady = true
        } catch (_: Exception) {
            Log.w(TAG, "static preview setup failed")
            fail(target, resetHolder = true)
        }
    }

    private fun succeed(target: Target, bitmap: Bitmap) {
        val current = active ?: return
        if (current.target !== target) return
        disposeActive(resetHolder = false)

        cleaningUp = true
        try {
            // The cache is URL keyed, so all still-valid occurrences in the
            // favorites and category rows can reuse this one captured bitmap.
            frameStore.put(
                target.videoUrl,
                bitmap,
                LivePreviewFrameOrigin.VISIBLE_STATIC_CAPTURE
            )
            val matchingTargets = targets.values.filter { it.videoUrl == target.videoUrl }
            matchingTargets.forEach { candidate ->
                candidate.holder.showCapturedPreviewFrameIfVisibleAndUnfocused(
                    candidate.requestKey,
                    bitmap
                )
                targets.remove(candidate.holder)
            }
            requestQueue.remove(target.videoUrl)
        } finally {
            cleaningUp = false
        }
        pump()
    }

    private fun fail(target: Target, resetHolder: Boolean) {
        val current = active
        if (current != null) {
            if (current.target !== target) return
            disposeActive(resetHolder)
        } else if (resetHolder) {
            target.holder.resetPreviewLayerIfBound(target.requestKey)
        }
        failedUrls.add(target.videoUrl)
        pump()
    }

    private fun cancelActive(resetHolder: Boolean) {
        if (active == null) return
        disposeActive(resetHolder)
    }

    private fun disposeActive(resetHolder: Boolean) {
        val current = active ?: return
        active = null
        PreviewStreamTelemetry.stopped(current.telemetryToken)
        timeoutTask?.let(handler::removeCallbacks)
        timeoutTask = null
        generation += 1

        cleaningUp = true
        try {
            current.player.removeListener(current.listener)
            runCatching { current.player.clearVideoTextureView(current.textureView) }
            runCatching { current.player.stop() }
            runCatching { current.player.clearMediaItems() }
            runCatching { current.player.release() }
            if (resetHolder) {
                current.target.holder.resetPreviewLayerIfBound(current.target.requestKey)
            }
        } finally {
            cleaningUp = false
        }
    }

    private fun removeTargetsForUrl(videoUrl: String) {
        val iterator = targets.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.videoUrl == videoUrl) iterator.remove()
        }
        requestQueue.remove(videoUrl)
    }

    private fun removeQueuedUrlIfUnused(videoUrl: String) {
        if (targets.values.none { it.videoUrl == videoUrl } && active?.target?.videoUrl != videoUrl) {
            requestQueue.remove(videoUrl)
            failedUrls.remove(videoUrl)
        }
    }

    private fun captureRenderedFrame(textureView: TextureView): Bitmap? {
        return runCatching { textureView.bitmap }
            .getOrNull()
            ?.takeIf { bitmap ->
                !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
            }
    }

    private fun scheduleSettleTask() {
        if (settleTask != null) return
        val deadline = settleDeadlineMs ?: return
        val delay = VisiblePreviewSettlePolicy.remainingDelay(SystemClock.uptimeMillis(), deadline)
        settleTask = Runnable {
            settleTask = null
            if (paused || cleaningUp) return@Runnable
            val currentDeadline = settleDeadlineMs
            if (currentDeadline != null &&
                VisiblePreviewSettlePolicy.remainingDelay(SystemClock.uptimeMillis(), currentDeadline) > 0L
            ) {
                scheduleSettleTask()
                return@Runnable
            }
            settleDeadlineMs = null
            settledSinceViewportChange = true
            // pump() rechecks every holder's binding, visibility, focus, and
            // frame cache after the viewport has settled.
            pump()
        }.also { handler.postDelayed(it, delay) }
    }

    private fun cancelSettleTask() {
        settleTask?.let(handler::removeCallbacks)
        settleTask = null
    }

    private fun previewKey(movie: Movie): String {
        return "${movie.id}:${movie.videoUrl.orEmpty()}"
    }

    private data class Target(
        val movie: Movie,
        val requestKey: String,
        val videoUrl: String,
        val holder: CardPresenter.CardViewHolder
    )

    private data class Active(
        val token: Long,
        val target: Target,
        val player: ExoPlayer,
        val textureView: TextureView,
        val listener: Player.Listener,
        val telemetryToken: Long
    )

    companion object {
        private const val TAG = "VisibleLivePreview"
        // A stalled URL must yield the single capture slot quickly so that
        // another currently visible row can still receive a static frame.
        private const val STATIC_CAPTURE_TIMEOUT_MS = 2_500L
        private const val INITIAL_SETTLE_DELAY_MS = 180L
        private const val VIEWPORT_QUIET_PERIOD_MS = 300L
        private const val FOCUS_RELEASE_SETTLE_MS = 180L
    }
}
