package com.android.tv

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlin.math.abs

/**
 * 简化的频道适配器，ViewHolder管理自己的预览生成，使用LRU缓存
 * 修复：延迟创建TextureView直到布局完成
 */
class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val spanCount: Int
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    private var recyclerView: RecyclerView? = null
    val previewCache: LruCache<String, Bitmap> = LruCache(calculateCacheSize())
    private var firstVisiblePosition = 0
    private var lastVisiblePosition = 0
    private val preloadOffset = spanCount * 3

    companion object {
        private const val TAG = "PhoneChannelAdapter"
        private const val PREVIEW_DURATION_MS = 3000L
        private const val MAX_CONCURRENT_PREVIEWS = 4
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        previewCache.evictAll()
        this.recyclerView = null
    }

    fun updateVisibleRange(firstVisible: Int, lastVisible: Int) {
        firstVisiblePosition = firstVisible
        lastVisiblePosition = lastVisible
        
        // 通知可见ViewHolder开始生成预览
        for (position in firstVisible..lastVisible) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
            holder?.startPreviewIfNeeded()
        }
    }

    fun refreshPlayers() {
        val layoutManager = recyclerView?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
        val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: firstVisible
        updateVisibleRange(firstVisible, lastVisible)
    }

    fun calculatePriority(position: Int): Int {
        val screenCenter = (firstVisiblePosition + lastVisiblePosition) / 2
        return abs(position - screenCenter)
    }

    private fun calculateCacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        return (maxMemory / 8).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view, this, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cleanup()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        itemView: View,
        private val adapter: PhoneChannelAdapter,
        private val onClick: (Movie) -> Unit
    ) : RecyclerView.ViewHolder(itemView), TextureView.SurfaceTextureListener {
        
        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)
        private val videoContainer: FrameLayout = itemView.findViewById(R.id.video_container)
        
        private var textureView: TextureView? = null
        private var player: ExoPlayer? = null
        private var movie: Movie? = null
        private var currentVideoUrl: String? = null
        private var hasPreview = false
        private var isSurfaceReady = false
        private var currentPosition: Int = -1
        private var shouldGeneratePreview = false

        fun bind(movie: Movie, position: Int) {
            this.movie = movie
            this.currentPosition = position
            title.text = movie.title
            
            // 加载封面图
            if (movie.cardImageUrl != null) {
                Glide.with(itemView.context)
                    .load(movie.cardImageUrl)
                    .placeholder(android.graphics.drawable.ColorDrawable(0xFF333333.toInt()))
                    .into(image)
            } else {
                image.setImageDrawable(null)
                image.setBackgroundColor(0xFF333333.toInt())
            }
            
            itemView.setOnClickListener { onClick(movie) }
            
            // 重置状态
            hasPreview = false
            isSurfaceReady = false
            shouldGeneratePreview = false
            cleanupTextureView()
            
            // 检查缓存
            val videoUrl = movie.videoUrl
            if (videoUrl != null) {
                currentVideoUrl = videoUrl
                val cachedBitmap = adapter.previewCache.get(videoUrl)
                if (cachedBitmap != null) {
                    showPreview(cachedBitmap)
                } else {
                    // 标记需要生成预览，等待布局完成
                    shouldGeneratePreview = true
                }
            }
        }

        /**
         * 在布局完成后调用，开始预览生成
         */
        fun startPreviewIfNeeded() {
            if (!shouldGeneratePreview || hasPreview || currentVideoUrl == null) return
            
            val priority = adapter.calculatePriority(currentPosition)
            if (priority <= MAX_CONCURRENT_PREVIEWS) {
                shouldGeneratePreview = false
                startPreviewGeneration(currentVideoUrl!!)
            }
        }

        private fun startPreviewGeneration(videoUrl: String) {
            createTextureView()
        }

        private fun createTextureView() {
            cleanupTextureView()
            
            val newTextureView = TextureView(itemView.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = this@ViewHolder
            }
            
            textureView = newTextureView
            videoContainer.addView(newTextureView, 0)
            image.visibility = View.GONE
            
            android.util.Log.d(TAG, "Position $currentPosition: TextureView created, waiting for SurfaceTexture...")
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            android.util.Log.d(TAG, "Position $currentPosition: SurfaceTexture available ${width}x${height}")
            isSurfaceReady = true
            currentVideoUrl?.let { startPlayback(it) }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            isSurfaceReady = false
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

        private fun startPlayback(videoUrl: String) {
            if (!isSurfaceReady) return
            
            val newPlayer = ExoPlayer.Builder(itemView.context).build().apply {
                volume = 0f
                playWhenReady = true
            }
            
            player = newPlayer
            
            textureView?.surfaceTexture?.let { surfaceTexture ->
                newPlayer.setVideoSurface(Surface(surfaceTexture))
                newPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
                newPlayer.prepare()
                newPlayer.play()
                
                android.util.Log.d(TAG, "Position $currentPosition: playback started")
                
                itemView.postDelayed({
                    captureFrame(videoUrl)
                }, PREVIEW_DURATION_MS)
            }
        }

        private fun captureFrame(videoUrl: String) {
            val texture = textureView
            if (texture == null) {
                android.util.Log.w(TAG, "Position $currentPosition: texture is null")
                return
            }
            
            try {
                player?.stop()
                player?.playWhenReady = false
                
                val bitmap = texture.bitmap
                android.util.Log.d(TAG, "Position $currentPosition: captured bitmap=${bitmap != null}")
                
                if (bitmap != null) {
                    adapter.previewCache.put(videoUrl, bitmap)
                    showPreview(bitmap)
                    updateOtherHolders(videoUrl, bitmap)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Position $currentPosition: error capturing frame", e)
            }
        }

        fun showPreview(bitmap: Bitmap) {
            if (hasPreview) return
            
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            hasPreview = true
            textureView?.visibility = View.INVISIBLE
            
            android.util.Log.d(TAG, "Position $currentPosition: preview displayed")
        }

        private fun updateOtherHolders(videoUrl: String, bitmap: Bitmap) {
            val layoutManager = recyclerView?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager ?: return
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            
            for (pos in firstVisible..lastVisible) {
                if (pos == currentPosition) continue
                val movie = currentList.getOrNull(pos) ?: continue
                if (movie.videoUrl == videoUrl) {
                    val holder = recyclerView?.findViewHolderForAdapterPosition(pos) as? ViewHolder
                    holder?.showPreview(bitmap)
                }
            }
        }

        fun cleanup() {
            cleanupTextureView()
            hasPreview = false
            isSurfaceReady = false
            shouldGeneratePreview = false
        }

        private fun cleanupTextureView() {
            player?.release()
            player = null
            textureView?.let { videoContainer.removeView(it) }
            textureView = null
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}
