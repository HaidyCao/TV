package com.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore

/**
 * 单例的预览生成器，管理隐藏的 TextureView 和 ExoPlayer
 * 使用优先级队列处理预览请求，优先处理可见项
 */
@UnstableApi
object PreviewGenerator {
    private const val TAG = "PreviewGenerator"
    private const val PREVIEW_DURATION_MS = 1000L
    private const val MAX_CONCURRENT_GENERATORS = 3 // 最大并发数

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // 信号量，用于严格控制并发数
    private val semaphore = Semaphore(MAX_CONCURRENT_GENERATORS, true)

    // 预览缓存，视频URL -> 预览图片
    val previewCache: LruCache<String, Bitmap> = LruCache(calculateCacheSize())

    // 优先级队列，处理预览请求
    private val requestQueue = PriorityBlockingQueue<PreviewRequest>()

    private val activeTasksLock = Any() // Lock object for activeTasks
    // 当前正在处理的任务
    private val activeTasks = mutableMapOf<Int, PreviewWorker>()

    // 下一个请求ID
    private var nextRequestId = 0

    // 预览完成回调
    private var onPreviewReady: ((String, Bitmap) -> Unit)? = null

    // 渲染器工厂（复用），供 PreviewWorker 访问
    internal var renderersFactory: DefaultRenderersFactory? = null

    // 隐藏的父容器（用于创建 TextureView）
    private var hiddenContainer: FrameLayout? = null

    /**
     * 初始化预览生成器
     * @param container 隐藏容器（必须已添加到窗口）
     * @param onPreviewReady 预览完成回调
     */
    fun init(container: FrameLayout, onPreviewReady: (String, Bitmap) -> Unit) {
        Log.d(TAG, "[INIT] PreviewGenerator.init called, cache size=${previewCache.size()}")
        this.onPreviewReady = onPreviewReady
        this.hiddenContainer = container

        // 初始化渲染器工厂
        if (renderersFactory == null) {
            renderersFactory = DefaultRenderersFactory(container.context.applicationContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }

        // 启动请求处理协程
        startRequestProcessor()
    }

    /**
     * 请求生成预览
     * 如果已在队列中，会更新其优先级
     * 如果正在处理中，会尝试更新优先级（但可能影响较小）
     */
    fun requestPreview(videoUrl: String, priority: Int, width: Int, height: Int) {
        // 检查是否已缓存
        if (previewCache.get(videoUrl) != null) {
            Log.d(TAG, "[CACHE] Preview already cached: $videoUrl, cache size=${previewCache.size()}")
            return
        }

        // 检查是否已在队列或正在处理
        val isQueued = requestQueue.any { it.videoUrl == videoUrl }
        val isActive = synchronized(activeTasksLock) { activeTasks.values.any { it.request.videoUrl == videoUrl } }

        if (isQueued || isActive) {
            // 已存在，尝试更新优先级
            updatePriority(videoUrl, priority)
            return
        }

        val request = PreviewRequest(
            id = nextRequestId++,
            videoUrl = videoUrl,
            priority = priority,
            width = width,
            height = height
        )

        Log.d(TAG, "[QUEUE] Added request: $videoUrl (priority=$priority), cache size=${previewCache.size()}")
        requestQueue.offer(request)
    }

    /**
     * 更新请求的优先级
     * @param videoUrl 视频 URL
     * @param newPriority 新优先级
     */
    fun updatePriority(videoUrl: String, newPriority: Int) {
        // 查找并更新队列中的请求
        val existingRequest = requestQueue.find { it.videoUrl == videoUrl }
        if (existingRequest != null) {
            requestQueue.remove(existingRequest)
            existingRequest.priority = newPriority
            requestQueue.offer(existingRequest)
            Log.d(TAG, "[PRIORITY] Updated: $videoUrl -> $newPriority")
        }

        // 查找并更新活跃任务
        synchronized(activeTasksLock) {
            val activeTask = activeTasks.values.find { it.request.videoUrl == videoUrl }
            if (activeTask != null) {
                activeTask.request.priority = newPriority
                Log.d(TAG, "[PRIORITY] Updated active: $videoUrl -> $newPriority")
            }
        }
    }

    /**
     * 取消指定视频的预览请求
     */
    fun cancelRequest(videoUrl: String) {
        // 从队列中移除
        requestQueue.removeIf { it.videoUrl == videoUrl }

        // 取消活跃任务
        synchronized(activeTasksLock) {
            val task = activeTasks.values.find { it.request.videoUrl == videoUrl }
            task?.cancel()
        }
        Log.d(TAG, "[CANCEL] Request cancelled: $videoUrl")
    }

    /**
     * 只清空预览请求队列，保留预览缓存
     */
    fun clearQueue() {
        Log.d(TAG, "[CLEAR] Clearing preview request queue, cache size=${previewCache.size()}")
        requestQueue.clear()
        synchronized(activeTasksLock) {
            activeTasks.values.forEach { it.cancel() }
            activeTasks.clear()
        }
    }

    /**
     * 清空所有请求和缓存
     */
    fun clearAll() {
        Log.d(TAG, "[CLEAR] Clearing all requests and cache, cache size=${previewCache.size()}")
        clearQueue()
        previewCache.evictAll()
    }

    /**
     * 启动请求处理协程
     */
    private fun startRequestProcessor() {
        scope.launch {
            while (isActive) {
                try {
                    // 等待获取信号量许可证，严格控制并发数
                    semaphore.acquire()
                    Log.d(TAG, "[QUEUE] Semaphore acquired, permits=${semaphore.availablePermits()}")

                    // 从队列取出请求
                    val request = requestQueue.poll()
                    if (request != null) {
                        processRequest(request)
                    } else {
                        // 没有请求，释放许可证
                        semaphore.release()
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[ERROR] Request processor error", e)
                    // 确保在异常情况下释放许可证
                    try {
                        semaphore.release()
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
    }

    /**
     * 处理单个请求
     */
    private fun processRequest(request: PreviewRequest) {
        Log.d(TAG, "[PROCESS] Processing: ${request.videoUrl}, semaphore permits=${semaphore.availablePermits()}")

        val container = hiddenContainer ?: run {
            Log.e(TAG, "[ERROR] Hidden container not initialized")
            semaphore.release()
            return
        }

        val worker = PreviewWorker(request, container, onPreviewReady)
        synchronized(activeTasksLock) {
            activeTasks[request.id] = worker
        }

        scope.launch {
            try {
                worker.generate()
            } finally {
                synchronized(activeTasksLock) {
                    activeTasks.remove(request.id)
                }
                semaphore.release()
                Log.d(TAG, "[PROCESS] Completed: ${request.videoUrl}, semaphore permits=${semaphore.availablePermits()}")
            }
        }
    }

    /**
     * 计算缓存大小
     */
    private fun calculateCacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        return (maxMemory / 8).toInt()
    }

    /**
     * 销毁，释放资源
     */
    fun destroy() {
        Log.d(TAG, "[DESTROY] Destroying PreviewGenerator")
        scope.cancel()
        clearAll()
        renderersFactory = null
        hiddenContainer = null
        onPreviewReady = null
    }
}

/**
 * 预览请求数据类
 */
private data class PreviewRequest(
    val id: Int,
    val videoUrl: String,
    var priority: Int,
    val width: Int,
    val height: Int
) : Comparable<PreviewRequest> {
    // 优先级越小越高
    override fun compareTo(other: PreviewRequest): Int {
        return this.priority.compareTo(other.priority)
    }
}

/**
 * 预览工作器，负责创建 TextureView 和 ExoPlayer 并生成预览
 * 使用 CompletableDeferred 确保任务完整执行后才从 generate() 返回
 */
@UnstableApi
private class PreviewWorker(
    val request: PreviewRequest,
    private val container: FrameLayout,
    private val onPreviewReady: ((String, Bitmap) -> Unit)?
) {
    private val TAG = "PreviewWorker[${request.id}]"
    private var textureView: TextureView? = null
    private var player: ExoPlayer? = null
    private var isCancelled = false
    private var captureJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val jobCompleted = CompletableDeferred<Unit>()

    /**
     * 生成预览
     * 这个 suspend 函数会在整个预览任务完成后才返回
     */
    suspend fun generate() {
        if (isCancelled) return

        withContext(Dispatchers.Main) {
            if (isCancelled) {
                jobCompleted.complete(Unit)
                return@withContext
            }

            Log.d(TAG, "[GENERATE] Creating TextureView")

            try {
                // 创建 TextureView
                textureView = TextureView(container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(request.width, request.height)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            Log.d(TAG, "[SURFACE] Available: ${width}x${height}")
                            startPlayback()
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            Log.d(TAG, "[SURFACE] Destroyed")
                            releasePlayer()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }

                container.addView(textureView)
                Log.d(TAG, "[GENERATE] TextureView added to container")

            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Failed to create TextureView", e)
                cleanup()
            }
        }

        // 等待任务完成（成功或失败）或被取消
        try {
            jobCompleted.await()
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Generation interrupted", e)
            if (!jobCompleted.isCompleted) {
                jobCompleted.complete(Unit)
            }
        }
    }

    /**
     * 开始播放
     */
    private fun startPlayback() {
        if (isCancelled) return

        val texture = textureView ?: return
        val videoUrl = request.videoUrl

        Log.d(TAG, "[PLAYBACK] Starting: $videoUrl")

        try {
            val factory = PreviewGenerator.renderersFactory ?: run {
                Log.e(TAG, "[ERROR] Renderers factory not available")
                cleanup()
                return
            }

            val newPlayer = ExoPlayer.Builder(container.context.applicationContext, factory)
                .setLooper(Looper.getMainLooper())
                .build().apply {
                    volume = 0f
                    playWhenReady = true
                }

            player = newPlayer
            newPlayer.setVideoTextureView(texture)
            newPlayer.setMediaItem(MediaItem.fromUri(videoUrl))

            newPlayer.addListener(object : androidx.media3.common.Player.Listener {
                private var captureScheduled = false

                override fun onPlaybackStateChanged(state: Int) {
                    if (isCancelled || newPlayer.isReleased) return

                    // 当播放器状态变为 ready 时直接捕获首帧，无需等待 150ms
                    if (state == androidx.media3.common.Player.STATE_READY && !captureScheduled) {
                        captureScheduled = true
                        Log.d(TAG, "[PLAYBACK] Player ready, capturing first frame")
                        captureFrame()
                    } else if (state == androidx.media3.common.Player.STATE_IDLE && newPlayer.playerError != null) {
                        Log.w(TAG, "[PLAYBACK] Error: ${newPlayer.playerError}")
                        cleanup()
                    }
                }
            })

            newPlayer.prepare()
            Log.d(TAG, "[PLAYBACK] Player prepared")

        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to start playback", e)
            cleanup()
        }
    }

    /**
     * 捕获帧
     */
    private fun captureFrame() {
        if (isCancelled) return

        val texture = textureView
        val p = player

        if (texture == null || p == null) {
            Log.w(TAG, "[CAPTURE] Aborted: texture=$texture, player=$p")
            cleanup()
            return
        }

        Log.d(TAG, "[CAPTURE] Capturing frame")

        try {
            val bitmap = texture.bitmap
            if (bitmap != null) {
                Log.d(TAG, "[CAPTURE] Success: ${bitmap.width}x${bitmap.height}")

                // 立即停止播放，节省资源
                try {
                    p.stop()
                    Log.d(TAG, "[CAPTURE] Playback stopped successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "[CAPTURE] Failed to stop playback", e)
                }

                // 放入缓存
                PreviewGenerator.previewCache.put(request.videoUrl, bitmap)

                // 通知回调
                mainHandler.post {
                    if (!isCancelled) {
                        onPreviewReady?.invoke(request.videoUrl, bitmap)
                    }
                }

                cleanup()
            } else {
                Log.w(TAG, "[CAPTURE] Failed: bitmap is null")
                cleanup()
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to capture frame", e)
            cleanup()
        }
    }

    /**
     * 释放播放器
     */
    private fun releasePlayer() {
        val p = player
        if (p != null) {
            player = null
            p.clearVideoTextureView(textureView)
            try {
                p.stop()
                p.release()
            } catch (e: Exception) {
                Log.e(TAG, "[ERROR] Failed to release player", e)
            }
        }
    }

    /**
     * 清理资源
     */
    private fun cleanup() {
        Log.d(TAG, "[CLEANUP] Cleaning up")

        captureJob?.cancel()
        captureJob = null

        textureView?.let {
            it.surfaceTextureListener = null
            container.removeView(it)
        }
        textureView = null

        releasePlayer()

        // 通知任务完成
        if (!jobCompleted.isCompleted) {
            jobCompleted.complete(Unit)
        }
    }

    /**
     * 取消任务
     */
    fun cancel() {
        if (!isCancelled) {
            isCancelled = true
            Log.d(TAG, "[CANCEL] Worker cancelled")
            mainHandler.post { cleanup() }
        }
    }
}

