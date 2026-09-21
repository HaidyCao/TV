package com.android.tv

import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlin.math.abs
import kotlin.math.min

/**
 * Mobile channel grid with one muted preview player at most.
 *
 * A single player is deliberately reused for the card nearest the viewport
 * center after scrolling stops; this prevents a grid from opening many live
 * streams at once.
 */
class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val onToggleFavorite: (Movie) -> Unit,
    @Suppress("UNUSED_PARAMETER") initialPlayerPoolSize: Int = 1
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    private var recyclerView: RecyclerView? = null
    private var previewPlayer: ExoPlayer? = null
    private var previewHolder: ViewHolder? = null
    private var previewTask: Runnable? = null
    private var favoriteKeys: Set<String> = emptySet()
    private var previewEnabled = true

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        schedulePreviewUpdate()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        releasePreviewPlayer()
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /** Kept for the existing grid-size control; preview count remains one. */
    fun updatePoolSize(@Suppress("UNUSED_PARAMETER") newSize: Int) {
        schedulePreviewUpdate()
    }

    fun setPreviewEnabled(enabled: Boolean) {
        if (previewEnabled == enabled) {
            if (!enabled) {
                cancelScheduledPreview()
                clearPreview(releasePlayer = true)
            }
            return
        }
        previewEnabled = enabled
        if (enabled) {
            schedulePreviewUpdate()
        } else {
            cancelScheduledPreview()
            clearPreview(releasePlayer = true)
        }
    }

    fun refreshPlayers() = schedulePreviewUpdate()

    fun updateFavorites(newFavoriteKeys: Set<String>) {
        if (favoriteKeys == newFavoriteKeys) return
        favoriteKeys = newFavoriteKeys
        val recycler = recyclerView ?: return
        repeat(recycler.childCount) { index ->
            (recycler.getChildViewHolder(recycler.getChildAt(index)) as? ViewHolder)
                ?.updateFavoriteBadge()
        }
    }

    fun schedulePreviewUpdate() {
        cancelScheduledPreview()
        if (!previewEnabled) return
        previewTask = Runnable { startPreviewForViewportCenter() }
        recyclerView?.postDelayed(previewTask!!, PREVIEW_DELAY_MS)
    }

    fun pausePreview() {
        cancelScheduledPreview()
        clearPreview()
    }

    fun releasePreviewPlayer() {
        cancelScheduledPreview()
        clearPreview(releasePlayer = true)
    }

    private fun cancelScheduledPreview() {
        previewTask?.let { task -> recyclerView?.removeCallbacks(task) }
        previewTask = null
    }

    private fun startPreviewForViewportCenter() {
        if (!previewEnabled) {
            clearPreview(releasePlayer = true)
            return
        }
        val recycler = recyclerView ?: return
        if (recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return

        val candidate = findPreviewCandidate(recycler) ?: run {
            clearPreview()
            return
        }
        if (candidate === previewHolder) {
            previewPlayer?.play()
            return
        }
        attachPreview(candidate)
    }

    private fun findPreviewCandidate(recycler: RecyclerView): ViewHolder? {
        val layoutManager = recycler.layoutManager as? GridLayoutManager ?: return null
        val firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: layoutManager.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return null

        val rowStart = firstVisible - (firstVisible % layoutManager.spanCount)
        val viewportCenter = recycler.height / 2
        return (rowStart until min(rowStart + layoutManager.spanCount, itemCount))
            .mapNotNull { recycler.findViewHolderForAdapterPosition(it) as? ViewHolder }
            .filter { !it.movie?.videoUrl.isNullOrBlank() }
            .minByOrNull { holder ->
                abs((holder.itemView.top + holder.itemView.bottom) / 2 - viewportCenter)
            }
    }

    private fun attachPreview(holder: ViewHolder) {
        if (!previewEnabled) {
            clearPreview(releasePlayer = true)
            return
        }
        val movie = holder.movie ?: return
        val videoUrl = movie.videoUrl ?: return
        clearPreview()

        val player = previewPlayer ?: ExoPlayer.Builder(holder.itemView.context).build().also { createdPlayer ->
            createdPlayer.volume = 0f
            createdPlayer.repeatMode = Player.REPEAT_MODE_OFF
            createdPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) previewHolder?.showPreviewFrame()
                }

                override fun onPlayerError(error: PlaybackException) {
                    previewHolder?.hidePreviewFrame()
                }
            })
            previewPlayer = createdPlayer
        }

        val textureView = holder.ensurePreviewTexture()
        previewHolder = holder
        player.setVideoTextureView(textureView)
        player.setMediaItem(MediaItem.fromUri(videoUrl))
        player.prepare()
        player.playWhenReady = true
    }

    private fun clearPreview(releasePlayer: Boolean = false) {
        val player = previewPlayer
        val holder = previewHolder
        if (holder != null) {
            if (player != null) {
                holder.previewTextureView?.let { textureView ->
                    player.clearVideoTextureView(textureView)
                }
            }
            holder.removePreviewTexture()
        }
        previewHolder = null

        if (releasePlayer) {
            player?.release()
            previewPlayer = null
        } else {
            player?.stop()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        if (holder === previewHolder) clearPreview()
        holder.unbind()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)
        private val placeholder: TextView = itemView.findViewById(R.id.channel_placeholder)
        private val videoContainer: ViewGroup = itemView.findViewById(R.id.video_container)
        private val liveBadge: TextView = itemView.findViewById(R.id.live_badge)
        private val categoryBadge: TextView = itemView.findViewById(R.id.category_badge)
        private val favoriteBadge: ImageView = itemView.findViewById(R.id.favorite_badge)

        var movie: Movie? = null
            private set
        var previewTextureView: TextureView? = null
            private set

        fun bind(newMovie: Movie) {
            if (this === previewHolder && movie?.id != newMovie.id) clearPreview()
            movie = newMovie
            title.text = newMovie.title
            liveBadge.visibility = if (newMovie.isLive) View.VISIBLE else View.GONE
            categoryBadge.text = newMovie.category
            categoryBadge.visibility = if (newMovie.category.isNullOrBlank()) View.GONE else View.VISIBLE
            updateFavoriteBadge()

            Glide.with(itemView).clear(image)
            image.setImageDrawable(null)
            placeholder.text = placeholderLabel(newMovie.title)
            if (!newMovie.cardImageUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(newMovie.cardImageUrl)
                    .centerCrop()
                    .into(image)
            }

            itemView.setOnClickListener {
                if (this === previewHolder) clearPreview()
                onClick(newMovie)
            }
            itemView.setOnLongClickListener {
                onToggleFavorite(newMovie)
                true
            }
            hidePreviewFrame()
        }

        fun updateFavoriteBadge() {
            favoriteBadge.visibility = if (movie?.let { ChannelFavorites.isFavorite(it, favoriteKeys) } == true) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        fun ensurePreviewTexture(): TextureView {
            previewTextureView?.let {
                it.visibility = View.VISIBLE
                return it
            }
            return TextureView(itemView.context).also { textureView ->
                textureView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                textureView.visibility = View.VISIBLE
                videoContainer.addView(textureView, 1)
                previewTextureView = textureView
            }
        }

        fun showPreviewFrame() {
            previewTextureView?.visibility = View.VISIBLE
            image.visibility = View.GONE
            placeholder.visibility = View.GONE
        }

        fun hidePreviewFrame() {
            showArtwork()
            previewTextureView?.visibility = View.GONE
        }

        fun removePreviewTexture() {
            previewTextureView?.let(videoContainer::removeView)
            previewTextureView = null
            showArtwork()
        }

        private fun showArtwork() {
            placeholder.visibility = View.VISIBLE
            image.visibility = if (movie?.cardImageUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        private fun placeholderLabel(title: String?): String {
            return title.orEmpty()
                .replace("超高清", "")
                .replace("高清", "")
                .replace("超清", "")
                .trim()
                .ifBlank { itemView.context.getString(R.string.channel_placeholder_label) }
        }

        fun unbind() {
            Glide.with(itemView).clear(image)
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            movie = null
            removePreviewTexture()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie): Boolean = oldItem == newItem
    }

    companion object {
        private const val PREVIEW_DELAY_MS = 650L
    }
}
