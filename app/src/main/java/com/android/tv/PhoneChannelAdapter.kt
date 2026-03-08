package com.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

/**
 * 使用独立预览生成器的频道适配器
 * 预览生成集中在 PreviewGenerator 单例中，通过优先级队列管理
 */
class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit,
    private val spanCount: Int
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    private var recyclerView: RecyclerView? = null
    private var firstVisiblePosition = 0
    private var lastVisiblePosition = 0

    // 隐藏容器，用于预览生成（必须附加到窗口才能创建 Surface）
    private val hiddenContainer by lazy {
        FrameLayout(recyclerView?.context?.applicationContext ?: throw IllegalStateException("Context not set")).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            // 不能使用 GONE，否则 Surface 不会被创建
            // 使用透明度和极小尺寸
            alpha = 0f
            translationX = -10000f
            translationY = -10000f
        }
    }

    companion object {
        private const val TAG = "PhoneChannelAdapter"
        private const val PRIORITY_CURRENT_SCREEN = 0 // 当前屏幕显示的项优先级（最高）
        private const val PRIORITY_PREV_SCREEN = 100 // 上一屏的项优先级
        private const val PRIORITY_NEXT_SCREEN = 150 // 下一屏的项优先级
        // 更远的项不加载预览
        private const val PAYLOAD_PREVIEW_READY = "preview_ready"
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView

        // 将隐藏容器添加到 RecyclerView 的父容器中
        val parent = recyclerView.parent as? ViewGroup
        if (parent != null && hiddenContainer.parent == null) {
            parent.addView(hiddenContainer, 0)
            Log.d(TAG, "[LIFECYCLE] Hidden container added to parent, isAttached=${hiddenContainer.isAttachedToWindow}")
        }

        // 初始化预览生成器
        PreviewGenerator.init(hiddenContainer) { videoUrl, bitmap ->
            // 预览完成回调
            onPreviewReady(videoUrl, bitmap)
        }

        Log.d(TAG, "[LIFECYCLE] Attached to RecyclerView")
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        Log.d(TAG, "[LIFECYCLE] Detached from RecyclerView")

        // 移除隐藏容器
        val parent = hiddenContainer.parent as? ViewGroup
        parent?.removeView(hiddenContainer)

        // 清理预览生成器
        PreviewGenerator.clearAll()

        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    /**
     * 预览完成回调
     */
    private fun onPreviewReady(videoUrl: String, bitmap: Bitmap) {
        Log.d(TAG, "[PREVIEW] Ready: $videoUrl")

        // 查找所有匹配该视频 URL 的位置并刷新
        val positions = mutableListOf<Int>()
        for (i in 0 until currentList.size) {
            if (currentList[i].videoUrl == videoUrl) {
                positions.add(i)
            }
        }

        // 刷新匹配的项
        positions.forEach { position ->
            notifyItemChanged(position, PAYLOAD_PREVIEW_READY)
        }
    }

    /**
     * 更新可见范围，重新调度预览生成
     * 防抖机制：只有可见范围真正发生变化时才会调度
     */
    fun updateVisibleRange(firstVisible: Int, lastVisible: Int) {
        // 检查可见范围是否真的发生了变化
        if (firstVisible == firstVisiblePosition && lastVisible == lastVisiblePosition) {
            Log.d(TAG, "[VISIBLE] Skipping - range not changed: $firstVisible-$lastVisible")
            return // 范围未变化，跳过调度
        }

        android.util.Log.d(
            TAG,
            "[VISIBLE] Update: $firstVisiblePosition-$lastVisiblePosition → $firstVisible-$lastVisible"
        )

        firstVisiblePosition = firstVisible
        lastVisiblePosition = lastVisible

        // 重新调度预览请求
        schedulePreviews()
    }

    /**
     * 刷新播放器（重新调度预览）
     */
    fun refreshPlayers() {
        val layoutManager =
            recyclerView?.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
        val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: firstVisible
        updateVisibleRange(firstVisible, lastVisible)
    }

    /**
     * 调度预览生成
     * 优先级策略：
     * 1. 当前屏幕显示的项（最高优先级）
     * 2. 上一屏的项
     * 3. 下一屏的项
     * 4. 更远的项
     * 5. 背景项（最低优先级）
     */
    private fun schedulePreviews() {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
            ?: return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        val itemCount = currentList.size

        // 计算屏幕可见项的数量，用来估算一屏有多少项
        val visibleCount = if (lastVisible >= firstVisible) lastVisible - firstVisible + 1 else 0
        val oneScreenItemCount = if (visibleCount > 0) visibleCount else spanCount * 2 // 默认估算值

        // 计算各范围的边界
        val firstPrevScreen = (firstVisible - oneScreenItemCount).coerceAtLeast(0)
        val lastPrevScreen = firstVisible - 1
        val firstNextScreen = lastVisible + 1
        val lastNextScreen = (lastVisible + oneScreenItemCount).coerceAtMost(itemCount - 1)
        val firstFar = (lastNextScreen + 1).coerceAtMost(itemCount - 1)
        val lastFar = itemCount - 1

        Log.d(TAG, "[SCHEDULE] Total=$itemCount, Visible=$firstVisible-$lastVisible, " +
                "PrevScreen=$firstPrevScreen-$lastPrevScreen, " +
                "NextScreen=$firstNextScreen-$lastNextScreen")

        // 为每个 item 调度预览，使用 Map 来跟踪已处理的 videoUrl
        val processedVideoUrls = mutableMapOf<String, Int>() // videoUrl -> priority

        for (position in 0 until itemCount) {
            val movie = currentList[position]
            val videoUrl = movie.videoUrl ?: continue

            // 只加载当前屏和上下一屏的预览，更远的不加载
            val isInRange = position in firstPrevScreen..lastNextScreen
            if (!isInRange) {
                Log.d(TAG, "[SCHEDULE] Skipping far position: $position, URL: $videoUrl")
                continue
            }

            // 计算优先级：当前屏幕 > 上一屏 > 下一屏
            val priority = when {
                position in firstVisible..lastVisible -> PRIORITY_CURRENT_SCREEN
                position in firstPrevScreen..lastPrevScreen -> PRIORITY_PREV_SCREEN
                position in firstNextScreen..lastNextScreen -> PRIORITY_NEXT_SCREEN
                else -> continue // 更远的项不加载
            }

            // 去重逻辑：只保留相同 videoUrl 的最高优先级请求
            if (processedVideoUrls.containsKey(videoUrl)) {
                // 如果已存在，只更新更高优先级的请求
                if (priority < processedVideoUrls[videoUrl]!!) {
                    processedVideoUrls[videoUrl] = priority
                    PreviewGenerator.requestPreview(videoUrl, priority, 1080, 608)
                    Log.d(TAG, "[SCHEDULE] Updating priority for existing URL: $videoUrl -> $priority")
                }
            } else {
                // 新 URL，添加到处理过的列表中
                processedVideoUrls[videoUrl] = priority
                PreviewGenerator.requestPreview(videoUrl, priority, 1080, 608)
                Log.d(TAG, "[SCHEDULE] Adding new request: $videoUrl, priority=$priority")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PREVIEW_READY)) {
            // 预览就绪，只更新预览图
            val movie = getItem(position)
            val videoUrl = movie.videoUrl ?: return
            val bitmap = PreviewGenerator.previewCache.get(videoUrl)
            if (bitmap != null) {
                holder.showPreview(bitmap)
            }
        } else {
            // 完整绑定
            holder.bind(getItem(position), position)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cleanup()
        super.onViewRecycled(holder)
    }

    inner class ViewHolder(
        itemView: View,
        private val onClick: (Movie) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)
        private val videoContainer: View = itemView.findViewById(R.id.video_container)
        private val liveBadge: View = itemView.findViewById(R.id.live_badge)

        private var currentVideoUrl: String? = null

        fun bind(movie: Movie, position: Int) {
            currentVideoUrl = movie.videoUrl

            title.text = movie.title
            Log.d(TAG, "[BIND] Position=$position, title=${movie.title}")

            // 确保 video_container 可见，这样内部的 ImageView 才能显示
            videoContainer.visibility = View.VISIBLE

            // 隐藏直播标识
            liveBadge.visibility = View.GONE

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

            // 从缓存加载预览
            val videoUrl = movie.videoUrl
            if (videoUrl != null) {
                val cachedBitmap = PreviewGenerator.previewCache.get(videoUrl)
                if (cachedBitmap != null) {
                    showPreview(cachedBitmap)
                }
            }
        }

        fun showPreview(bitmap: Bitmap) {
            Log.d(TAG, "[PREVIEW] Showing for position=$bindingAdapterPosition")
            // 确保 video_container 可见
            videoContainer.visibility = View.VISIBLE
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
        }

        fun cleanup() {
            // 清理资源（不再需要清理播放器，由 PreviewGenerator 管理）
            currentVideoUrl = null
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}
