package com.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlin.math.abs

/**
 * 简化的频道适配器，ViewHolder管理自己的预览生成，使用LRU缓存
 * 修复：Handler on a dead thread 错误，采用更稳健的 ExoPlayer 生命周期管理
 * 通过在主线程 Looper 上延迟释放播放器，并确保在回收时完全清理。
 */
@UnstableApi
class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val spanCount: Int
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    var recyclerView: RecyclerView? = null
    val previewCache: LruCache<String, Bitmap> = LruCache(calculateCacheSize())
    private var firstVisiblePosition = 0
    private var lastVisiblePosition = 0

    // 跟踪活跃的 ViewHolder 以便在 RecyclerView 分离时清理
    private val attachedViewHolders = mutableSetOf<ViewHolder>()
    private var renderersFactory: DefaultRenderersFactory? = null

    companion object {
        private const val TAG = "PhoneChannelAdapter"
        private const val PREVIEW_DURATION_MS = 1000L
    }

    private fun getRenderersFactory(context: Context): DefaultRenderersFactory {
        if (renderersFactory == null) {
            renderersFactory = DefaultRenderersFactory(context.applicationContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        return renderersFactory!!
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        // 清理所有活跃的 ViewHolder 中的播放器
        val holders = attachedViewHolders.toList()
        attachedViewHolders.clear()
        holders.forEach { it.cleanup() }

        previewCache.evictAll()
        renderersFactory = null
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun updateVisibleRange(firstVisible: Int, lastVisible: Int) {
        android.util.Log.d(
            TAG,
            "updateVisibleRange: firstVisible=$firstVisible, lastVisible=$lastVisible"
        )
        firstVisiblePosition = firstVisible
        lastVisiblePosition = lastVisible

        // 通知可见ViewHolder开始生成预览
        for (position in firstVisible..lastVisible) {
            val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? ViewHolder
            holder?.startPreviewIfNeeded()
        }
    }

    fun refreshPlayers() {
        val layoutManager =
            recyclerView?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
        val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: firstVisible
        updateVisibleRange(firstVisible, lastVisible)
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

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedViewHolders.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        Log.d(TAG, "[TEST] onViewDetachedFromWindow() called")
        attachedViewHolders.remove(holder)
        holder.cleanup()
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Log.d(TAG, "[TEST] onViewRecycled() called")
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
        private var currentVideoUrl: String? = null
        private var hasPreview = false
        private var isSurfaceReady = false
        private var currentPosition: Int = -1

        fun bind(movie: Movie, position: Int) {
            this.currentPosition = position
            title.text = movie.title

            Log.d(TAG, "[TEST] bind() called for position=$position, movie=${movie.title}")

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
            cleanup()

            val videoUrl = movie.videoUrl
            Log.d(TAG, "[TEST] bind() videoUrl=${videoUrl != null}")
            if (videoUrl != null) {
                currentVideoUrl = videoUrl
                val cachedBitmap = adapter.previewCache.get(videoUrl)
                Log.d(TAG, "[TEST] bind() cachedBitmap=${cachedBitmap != null}")
                if (cachedBitmap != null) {
                    showPreview(cachedBitmap)
                } else {
                    Log.d(TAG, "[TEST] bind() shouldGeneratePreview=true")
                }
            }
        }

        fun startPreviewIfNeeded() {
            Log.d(TAG, "[TEST] startPreviewIfNeeded() called for pos=$currentPosition")
            Log.d(
                TAG,
                "[TEST] startPreviewIfNeeded() hasPreview=$hasPreview, currentVideoUrl=${currentVideoUrl != null}"
            )

            if (hasPreview || currentVideoUrl == null || textureView != null) {
                Log.w(
                    TAG,
                    "[TEST] startPreviewIfNeeded() SKIPPED: hasPreview=$hasPreview, hasUrl=${currentVideoUrl != null}"
                )
                return
            }

            Log.d(TAG, "[TEST] startPreviewIfNeeded() CREATING texture for pos=$currentPosition")
            createTextureView()
        }

        private fun createTextureView() {
            Log.d(TAG, "[TEST] createTextureView() called for pos=$currentPosition")
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
            Log.d(TAG, "[TEST] createTextureView() TextureView ADDED for pos=$currentPosition")
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d(
                TAG,
                "[TEST] onSurfaceTextureAvailable() pos=$currentPosition size=${width}x${height}"
            )
            isSurfaceReady = true
            currentVideoUrl?.let {
                Log.d(
                    TAG,
                    "[TEST] onSurfaceTextureAvailable() calling startPlayback for pos=$currentPosition"
                )
                startPlayback(it)
            } ?: Log.w(
                TAG,
                "[TEST] onSurfaceTextureAvailable() currentVideoUrl is null for pos=$currentPosition"
            )
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            isSurfaceReady = false
            cleanupPlayer()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

        private fun startPlayback(videoUrl: String) {
            Log.d(
                TAG,
                "[TEST] startPlayback() called for pos=$currentPosition, isSurfaceReady=$isSurfaceReady"
            )
            if (!isSurfaceReady || adapter.recyclerView == null) {
                Log.w(
                    TAG,
                    "[TEST] startPlayback() ABORTED for pos=$currentPosition: isSurfaceReady=$isSurfaceReady, recyclerView=${adapter.recyclerView != null}"
                )
                return
            }

            cleanupPlayer()

            try {
                val factory = adapter.getRenderersFactory(itemView.context)
                val newPlayer = ExoPlayer.Builder(itemView.context.applicationContext, factory)
                    .setLooper(Looper.getMainLooper())
                    .build().apply {
                        volume = 0f
                        playWhenReady = true
                    }

                player = newPlayer
                newPlayer.setVideoTextureView(textureView)
                newPlayer.setMediaItem(MediaItem.fromUri(videoUrl))

                // 添加监听器，等待真正开始播放后再延迟捕获
                newPlayer.addListener(object : androidx.media3.common.Player.Listener {
                    private var captureScheduled = false

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (newPlayer.isReleased) {
                            return
                        }

                        if (isPlaying && !captureScheduled) {
                            captureScheduled = true
                            Log.d(
                                TAG,
                                "[TEST] Player started playing, scheduling capture in ${PREVIEW_DURATION_MS}ms for pos=$currentPosition"
                            )

                            currentVideoUrl?.let { captureFrame(it) }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        Log.d(TAG, "onPlaybackStateChanged() called with: state = $state")
                        if (state == androidx.media3.common.Player.STATE_ENDED) {
                            Log.d(TAG, "[TEST] Playback ended for pos=$currentPosition")
                        } else if (state == androidx.media3.common.Player.STATE_IDLE && newPlayer.playerError != null) {
                            Log.w(
                                TAG,
                                "[TEST] Playback error for pos=$currentPosition: ${newPlayer.playerError}"
                            )
                            cleanup()
                        }
                    }
                })

                newPlayer.prepare()
                Log.d(
                    TAG,
                    "[TEST] startPlayback() player prepared, waiting for playback to start for pos=$currentPosition"
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error starting playback at pos $currentPosition", e)
                cleanup()
            }
        }

        private fun captureFrame(videoUrl: String) {
            Log.d(TAG, "[TEST] captureFrame() called for pos=$currentPosition")
            val texture = textureView
            if (texture == null || player == null) {
                Log.w(
                    TAG,
                    "[TEST] captureFrame() ABORTED for pos=$currentPosition: texture=${texture != null}, player=${player != null}"
                )
                cleanupPlayer()
                return
            }

            try {
                val bitmap = texture.bitmap
                Log.d(
                    TAG,
                    "[TEST] captureFrame() bitmap captured=${bitmap != null} for pos=$currentPosition"
                )
                if (bitmap != null) {
                    adapter.previewCache.put(videoUrl, bitmap)
                    Log.d(TAG, "[TEST] captureFrame() bitmap cached for pos=$currentPosition")
                    showPreview(bitmap)
                    updateOtherHolders(videoUrl, bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[TEST] captureFrame() EXCEPTION for pos=$currentPosition", e)
            } finally {
                cleanupTextureView()
            }
        }

        fun showPreview(bitmap: Bitmap) {
            Log.d(
                TAG,
                "[TEST] showPreview() called for pos=$currentPosition, hasPreview=$hasPreview"
            )
            if (hasPreview) {
                Log.d(
                    TAG,
                    "[TEST] showPreview() SKIPPED for pos=$currentPosition: already has preview"
                )
                return
            }
            cleanup()

            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            hasPreview = true
            textureView?.visibility = View.INVISIBLE
            Log.d(TAG, "[TEST] showPreview() SUCCESS for pos=$currentPosition")
        }

        private fun updateOtherHolders(videoUrl: String, bitmap: Bitmap) {
            val recyclerView = adapter.recyclerView ?: return
            val layoutManager =
                recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
                    ?: return
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val lastVisible = layoutManager.findLastVisibleItemPosition()

            for (pos in firstVisible..lastVisible) {
                if (pos == currentPosition) continue
                val m = adapter.currentList.getOrNull(pos) ?: continue
                if (m.videoUrl == videoUrl) {
                    val holder = recyclerView.findViewHolderForAdapterPosition(pos) as? ViewHolder
                    holder?.showPreview(bitmap)
                }
            }
        }

        fun cleanup() {
            Log.d(TAG, "[TEST] cleanup() called for pos=$currentPosition")
            // 移除所有与该 ViewHolder 相关的 Handler 回调
            cleanupTextureView()
            hasPreview = false
            isSurfaceReady = false
            Log.d(TAG, "[TEST] cleanup() completed for pos=$currentPosition")
        }

        private fun cleanupPlayer() {
            Log.d(TAG, "cleanupPlayer() called")
            val p = player
            if (p != null) {
                player = null
                p.clearVideoTextureView(textureView)
                // 使用 post 确保在主线程 Looper 的下一次迭代中释放，
                // 这样可以避免在回调过程中直接释放可能导致的 MediaCodec 线程死锁或异常。
                try {
                    p.stop()
                    p.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing player", e)
                }
            }
        }

        private fun cleanupTextureView() {
            cleanupPlayer()
            textureView?.let {
                it.surfaceTextureListener = null
                videoContainer.removeView(it)
            }
            textureView = null
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}
