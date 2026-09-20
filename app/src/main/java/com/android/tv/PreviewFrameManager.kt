package com.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 预览帧管理器 - 负责异步获取视频预览帧
 * 实现思路：播放视频→获取第一帧→停止播放→缓存帧
 */
class PreviewFrameManager(private val context: Context) {

    companion object {
        private const val TAG = "PreviewFrameManager"
        private const val MAX_CACHE_SIZE = 30 // 最大缓存数量
        private const val PREVIEW_TIME_MS = 500L // 获取第500ms的帧
    }

    // 内存缓存
    private val bitmapCache = LruCache<String, Bitmap>(MAX_CACHE_SIZE)

    // 正在加载的任务
    private val loadingJobs = ConcurrentHashMap<String, Job>()

    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 主线程 Handler
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 获取预览帧
     * @param videoUrl 视频URL
     * @param callback 回调 (Bitmap?)
     */
    fun getPreviewFrame(videoUrl: String, callback: (Bitmap?) -> Unit) {
        // 检查缓存
        val cachedBitmap = bitmapCache.get(videoUrl)
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            Log.d(TAG, "Cache hit for: $videoUrl")
            mainHandler.post { callback(cachedBitmap) }
            return
        }

        // 检查是否正在加载
        if (loadingJobs.containsKey(videoUrl)) {
            Log.d(TAG, "Already loading: $videoUrl")
            // 延迟重试
            mainHandler.postDelayed({
                getPreviewFrame(videoUrl, callback)
            }, 300)
            return
        }

        // 启动加载任务
        val job = scope.launch {
            val bitmap = loadPreviewFrame(videoUrl)

            if (bitmap != null && !bitmap.isRecycled) {
                // 缩放保存内存
                val scaledBitmap = scaleBitmap(bitmap, 313, 176) // TV卡尺寸
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                }
                // 缓存
                bitmapCache.put(videoUrl, scaledBitmap)

                mainHandler.post { callback(scaledBitmap) }
            } else {
                mainHandler.post { callback(null) }
            }

            loadingJobs.remove(videoUrl)
        }

        loadingJobs[videoUrl] = job
    }

    /**
     * 使用 MediaMetadataRetriever 获取预览帧
     */
    private suspend fun loadPreviewFrame(videoUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        var bitmap: Bitmap? = null

        try {
            Log.d(TAG, "Loading preview for: $videoUrl")
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoUrl, HashMap())

            // 获取第500ms的帧
            bitmap = retriever.getFrameAtTime(
                PREVIEW_TIME_MS * 1000, // 转换为微秒
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )

            Log.d(TAG, "Preview loaded: ${bitmap != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preview for: $videoUrl", e)
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing retriever", e)
            }
        }

        return@withContext bitmap
    }

    /**
     * 缩放 Bitmap
     */
    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scale = kotlin.math.min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 预加载预览帧
     */
    fun preloadPreviews(videoUrls: List<String>) {
        videoUrls.forEach { url ->
            getPreviewFrame(url) { _ ->
                // 预加载不处理回调
            }
        }
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        bitmapCache.evictAll()
        loadingJobs.forEach { (_, job) -> job.cancel() }
        loadingJobs.clear()

        Log.d(TAG, "Cache cleared")
    }

    /**
     * 释放资源
     */
    fun release() {
        clearCache()
        scope.cancel()
    }
}
