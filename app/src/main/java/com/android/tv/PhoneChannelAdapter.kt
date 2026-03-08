package com.android.tv

import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem

class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val initialPlayerPoolSize: Int = 4,
    private val previewDurationMs: Long = 3000L
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    private val playerPool = mutableListOf<ExoPlayer>()
    private val activeHolders = mutableSetOf<ViewHolder>()
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        initializePlayerPool()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        releasePlayerPool()
        this.recyclerView = null
    }

    private fun initializePlayerPool() {
        val context = recyclerView?.context ?: return
        repeat(initialPlayerPoolSize) {
            val player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                playWhenReady = true
            }
            playerPool.add(player)
        }
    }

    private fun releasePlayerPool() {
        playerPool.forEach { it.release() }
        playerPool.clear()
    }

    fun refreshPlayers() {
        val layoutManager = recyclerView?.layoutManager as? GridLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
        val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: firstVisible

        val visibleHolders = activeHolders.filter { holder ->
            val pos = holder.bindingAdapterPosition
            pos in firstVisible..lastVisible && holder.movie?.videoUrl != null && !holder.hasPreview()
        }.sortedBy { it.bindingAdapterPosition }

        val freePlayers = playerPool.filter { player ->
            !activeHolders.any { it.player === player }
        }

        visibleHolders.take(freePlayers.size).forEachIndexed { index, holder ->
            val player = freePlayers.getOrNull(index) ?: return@forEachIndexed
            holder.startPreview(player) {
                it.releasePlayerKeepPreview()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.onRecycled()
        activeHolders.remove(holder)
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)
        private val videoContainer: ViewGroup = itemView.findViewById(R.id.video_container)

        var player: ExoPlayer? = null
            private set
        var movie: Movie? = null
            private set
        private var isPlaying = false
        private var hasCapturedPreview = false

        private var textureView: TextureView? = null
        private var surfaceReady = false
        private var previewBitmap: android.graphics.Bitmap? = null

        private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        fun bind(movie: Movie) {
            this.movie = movie
            title.text = movie.title

            if (movie.cardImageUrl != null) {
                Glide.with(itemView.context)
                    .load(movie.cardImageUrl)
                    .into(image)
            } else {
                image.setBackgroundColor(0xFF333333.toInt())
            }

            itemView.setOnClickListener { onClick(movie) }
            activeHolders.add(this)

            if (hasCapturedPreview && previewBitmap != null) {
                image.setImageBitmap(previewBitmap)
                image.visibility = View.VISIBLE
                image.alpha = 1.0f
            }

            if (movie.videoUrl != null && !hasCapturedPreview) {
                recyclerView?.postDelayed({
                    refreshPlayers()
                }, 100)
            }
        }

        fun startPreview(exoPlayer: ExoPlayer, onComplete: (ViewHolder) -> Unit) {
            player = exoPlayer
            ensureTextureView()

            if (surfaceReady && textureView?.surfaceTexture != null) {
                startPlaying(exoPlayer, onComplete)
            } else {
                itemView.postDelayed({
                    if (player == exoPlayer) {
                        startPreview(exoPlayer, onComplete)
                    }
                }, 100)
            }
        }

        private fun startPlaying(exoPlayer: ExoPlayer, onComplete: (ViewHolder) -> Unit) {
            val surfaceTexture = textureView?.surfaceTexture ?: return

            exoPlayer.setVideoSurface(Surface(surfaceTexture))
            val mediaItem = MediaItem.fromUri(movie?.videoUrl ?: return)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.volume = 0f
            exoPlayer.prepare()
            exoPlayer.play()

            isPlaying = true

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isPlaying && player == exoPlayer) {
                    captureFrameAsPreview()
                    exoPlayer.playWhenReady = false
                    isPlaying = false
                    hideVideoAndShowPreview()
                    hasCapturedPreview = true
                    onComplete(this)
                }
            }, previewDurationMs)
        }

        private fun captureFrameAsPreview() {
            textureView?.let { tv ->
                try {
                    previewBitmap = tv.bitmap
                } catch (e: Exception) {
                    android.util.Log.e("PhoneChannelAdapter", "Failed to capture frame", e)
                }
            }
        }

        private fun hideVideoAndShowPreview() {
            textureView?.visibility = View.INVISIBLE

            previewBitmap?.let { bitmap ->
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                image.alpha = 1.0f
            }
        }

        private fun ensureTextureView() {
            if (textureView != null) return

            textureView = TextureView(itemView.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = this@ViewHolder.surfaceTextureListener
            }

            videoContainer.addView(textureView, 0)
            image.visibility = View.GONE
        }

        fun releasePlayerKeepPreview() {
            player?.let { exoPlayer ->
                exoPlayer.stop()
                exoPlayer.clearVideoSurface()
            }
            player = null
            isPlaying = false

            textureView?.let { tv ->
                videoContainer.removeView(tv)
            }
            textureView = null
            surfaceReady = false
        }

        fun onRecycled() {
            // 只释放播放器资源，保留预览图以便滚动回来时显示
            releasePlayerKeepPreview()
            // 重要：不清除 ImageView 的 Bitmap，这样预览图会保持显示
            // 也不回收 previewBitmap，保留它供下次使用
        }
        
        fun onDestroyed() {
            // ViewHolder 真正被销毁时才清理所有资源
            image.setImageDrawable(null)
            image.setImageBitmap(null)
            releasePlayerKeepPreview()
            previewBitmap?.recycle()
            previewBitmap = null
            hasCapturedPreview = false
        }

        fun hasPreview(): Boolean = hasCapturedPreview && previewBitmap != null
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}