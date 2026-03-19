package com.android.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import com.android.tv.util.isTelevision
import kotlinx.coroutines.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore

/**
 * 单例的预览生成器，管理隐藏的 TextureView 和 ExoPlayer
 * 使用优先级队列处理预览请求，优先处理可见项
 */
@SuppressLint("StaticFieldLeak")
@UnstableApi
object PreviewGenerator {

    public const val PRIORITY_CURRENT_SCREEN_SELECTED = 0 // 当前屏幕显示的项优先级（最高）
    public const val PRIORITY_CURRENT_SCREEN = 1 // 当前屏幕显示的项优先级（最高）
    public const val PRIORITY_PREV_SCREEN = 100 // 上一屏的项优先级
    public const val PRIORITY_NEXT_SCREEN = 150 // 下一屏的项优先级


    const val TAG = "PreviewGenerator"
    private const val MAX_CONCURRENT_NON_TV = 3
    private const val MAX_CONCURRENT_TV = 1

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)

    // 预览缓存，视频URL -> 预览图片
    val previewCache: LruCache<String, Bitmap> = LruCache(calculateCacheSize())

    // 失败的URL集合，缓存 Source error 的 URL，避免重复触发无效请求
    private val failedUrls = Collections.synchronizedSet(mutableSetOf<String>())

    // 优先级队列，处理预览请求
    private val requestQueue = PriorityBlockingQueue<PreviewRequest>()

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
        val context = container.context.applicationContext
        val maxConcurrent = if (isTelevision(context)) MAX_CONCURRENT_TV else MAX_CONCURRENT_NON_TV

        Log.d(
            TAG,
            "[INIT] PreviewGenerator.init called, cache size=${previewCache.size()}, maxConcurrent=$maxConcurrent"
        )
        this.onPreviewReady = onPreviewReady
        this.hiddenContainer = container

        // 初始化渲染器工厂
        if (renderersFactory == null) {
            renderersFactory = DefaultRenderersFactory(context)
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
    fun requestPreview(title: String, videoUrl: String, priority: Int, width: Int, height: Int) {
        // 检查是否已缓存
        if (previewCache.get(videoUrl) != null) {
            Log.d(
                TAG,
                "[CACHE] Preview already cached: $videoUrl, cache size=${previewCache.size()}"
            )
            return
        }

        // 检查是否已在失败缓存中
        if (failedUrls.contains(videoUrl)) {
            Log.d(
                TAG,
                "[FAILED_CACHE] URL previously failed with Source error: $videoUrl"
            )
            return
        }

        // 检查是否已在队列或正在处理
        val isQueued = requestQueue.any { it.videoUrl == videoUrl }

        if (isQueued) {
            // 已存在，尝试更新优先级
            updatePriority(videoUrl, priority)
            return
        }

        val request = PreviewRequest(
            id = nextRequestId++,
            videoUrl = videoUrl,
            videoName = title,
            priority = priority,
            width = width,
            height = height
        )

        Log.d(
            TAG,
            "[QUEUE] Added request: $videoUrl (priority=$priority), cache size=${previewCache.size()}"
        )
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
    }

    /**
     * 取消指定视频的预览请求
     */
    fun cancelRequest(videoUrl: String) {
        // 从队列中移除
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestQueue.removeIf { it.videoUrl == videoUrl }
        } else {
            requestQueue.removeAll { it.videoUrl == videoUrl }
        }
        Log.d(TAG, "[CANCEL] Request cancelled: $videoUrl")
    }

    /**
     * 只清空预览请求队列，保留预览缓存
     */
    fun clearQueue() {
        Log.d(TAG, "[CLEAR] Clearing preview request queue, cache size=${previewCache.size()}")
        requestQueue.clear();
    }

    /**
     * 清空所有请求和缓存
     */
    fun clearAll() {
        Log.d(TAG, "[CLEAR] Clearing all requests and cache, cache size=${previewCache.size()}")
        clearQueue()
        previewCache.evictAll()
        failedUrls.clear()
    }

    /**
     * 将 URL 标记为失败（通常是因为遇到非法的视频格式/源错误）
     */
    fun markAsFailed(videoUrl: String) {
        if (failedUrls.add(videoUrl)) {
            Log.d(TAG, "[FAILED] Marked as failed: $videoUrl")
        }
    }

    /**
     * 启动请求处理协程 (已修复死锁问题)
     */
    private fun startRequestProcessor() {
        scope.launch {
            while (isActive) {
                // 从队列取出请求 (不再先获取信号量)
                val request = requestQueue.take() // 使用 take() 阻塞等待，直到有请求可用
                executor.execute { processRequest(request) }
//                processRequest(request)
            }
        }
    }

    /**
     * 处理单个请求 (已修复死锁问题)
     */
    private fun processRequest(request: PreviewRequest) {
        try {
            // 检查是否已缓存或已失败
            if (previewCache.get(request.videoUrl) != null || failedUrls.contains(request.videoUrl)) {
                Log.d(TAG, "processRequest: [CACHE/FAILED] Skipping: ${request.videoUrl}")
                return
            }

            // 在协程开始时获取信号量
            Log.d(
                TAG,
                "[PROCESS] Acquired semaphore, processing: ${request.videoUrl}, title: ${request.videoName}}"
            )

            val container = hiddenContainer ?: run {
                Log.e(TAG, "[ERROR] Hidden container not initialized")
                return
            }

            val worker = PreviewWorker(request, container, onPreviewReady)
            worker.generate()
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Log.e(TAG, "[ERROR] Unhandled exception in processRequest for ${request.videoUrl}", e)
        } finally {
            Log.d(
                TAG,
                "[PROCESS] Released semaphore, completed: ${request.videoUrl}, title: ${request.videoName}"
            )
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
    val videoName: String,
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

    /**
     * 生成预览
     * 这个 suspend 函数会在整个预览任务完成后才返回
     */
    fun generate() {
        if (isCancelled) return

        val countDownLatch = CountDownLatch(1)
        mainHandler.post {
            if (PreviewGenerator.previewCache.get(request.videoUrl) != null) {
                Log.d(PreviewGenerator.TAG, "processRequest: [CACHE] Preview already cached: ${request.videoUrl}}")
                return@post
            }

            if (isCancelled) {
                countDownLatch.countDown()
                return@post
            }

            Log.d(TAG, "[GENERATE] Creating TextureView for ${request.videoName}")

            try {
                // 创建 TextureView
                textureView = TextureView(container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(request.width, request.height)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            Log.d(TAG, "[SURFACE] Available: ${width}x${height}")
                            startPlayback(countDownLatch)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                        }

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
                countDownLatch.countDown()
            }
        }

        // 等待任务完成（成功或失败）或被取消
        try {
            countDownLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            SystemClock.sleep(100)
        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Generation interrupted", e)
        }
    }

    /**
     * 开始播放
     */
    private fun startPlayback(countDownLatch: CountDownLatch) {
        if (isCancelled) {
            countDownLatch.countDown()
            return
        }

        val texture = textureView ?: return
        val videoUrl = request.videoUrl

        Log.d(TAG, "[PLAYBACK] Starting: $videoUrl")

        try {
            val factory = PreviewGenerator.renderersFactory ?: run {
                Log.e(TAG, "[ERROR] Renderers factory not available")
                cleanup()
                countDownLatch.countDown()
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
                    if (isCancelled || newPlayer.isReleased) {
                        countDownLatch.countDown()
                        return
                    }

                    // 当播放器状态变为 ready 时直接捕获首帧，无需等待 150ms
                    if (state == androidx.media3.common.Player.STATE_READY && !captureScheduled) {
                        captureScheduled = true
                        Log.d(TAG, "[PLAYBACK] Player ready, capturing first frame")
                        captureFrame(countDownLatch)
                    } else if (state == androidx.media3.common.Player.STATE_IDLE && newPlayer.playerError != null) {
                        val error = newPlayer.playerError
                        // 遇到 Source Error (通常是无法识别的输入格式) 就将其标记为不可用
                        if (error is androidx.media3.exoplayer.ExoPlaybackException && error.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE) {
                            Log.e(TAG, "[PLAYBACK] Source error detected for ${request.videoUrl}, marking as failed")
                            PreviewGenerator.markAsFailed(request.videoUrl)
                        }

                        Log.w(TAG, "[PLAYBACK] Error: $error")
                        cleanup()
                        countDownLatch.countDown()
                    }
                }
            })

            newPlayer.prepare()
            Log.d(TAG, "[PLAYBACK] Player prepared")

        } catch (e: Exception) {
            Log.e(TAG, "[ERROR] Failed to start playback", e)
            cleanup()
            countDownLatch.countDown()
        }
    }

    /**
     * 捕获帧
     */
    private fun captureFrame(countDownLatch: CountDownLatch) {
        if (isCancelled) {
            countDownLatch.countDown()
            return
        }

        val texture = textureView
        val p = player

        if (texture == null || p == null) {
            Log.w(TAG, "[CAPTURE] Aborted: texture=$texture, player=$p")
            cleanup()
            countDownLatch.countDown()
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
        countDownLatch.countDown()
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

