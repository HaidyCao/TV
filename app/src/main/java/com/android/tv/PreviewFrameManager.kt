package com.android.tv

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Optional thumbnail extractor for finite, on-demand videos.
 *
 * Live streams must use a logo or fallback artwork instead. Extracting a frame
 * from every live card starts real network streams while the user is browsing.
 */
class PreviewFrameManager {

    companion object {
        private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        private const val PREVIEW_TIME_US = 500_000L
        private const val EXTRACTION_TIMEOUT_MS = 5_000L
        private const val PREVIEW_WIDTH = 313
        private const val PREVIEW_HEIGHT = 176
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val lock = Any()
    private val callbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val loadingJobs = mutableMapOf<String, Job>()
    private val extractionSemaphore = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun getPreviewFrame(videoUrl: String, callback: (Bitmap?) -> Unit) {
        bitmapCache.get(videoUrl)?.takeUnless(Bitmap::isRecycled)?.let { bitmap ->
            mainHandler.post { callback(bitmap) }
            return
        }

        synchronized(lock) {
            callbacks.getOrPut(videoUrl) { mutableListOf() }.add(callback)
            if (loadingJobs.containsKey(videoUrl)) return

            loadingJobs[videoUrl] = scope.launch {
                val bitmap = extractionSemaphore.withPermit {
                    withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                        loadPreviewFrame(videoUrl)
                    }
                }?.let { scaleBitmap(it, PREVIEW_WIDTH, PREVIEW_HEIGHT) }

                bitmap?.let { bitmapCache.put(videoUrl, it) }
                deliver(videoUrl, bitmap)
            }
        }
    }

    private fun loadPreviewFrame(videoUrl: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoUrl, emptyMap())
            retriever.getFrameAtTime(
                PREVIEW_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun deliver(videoUrl: String, bitmap: Bitmap?) {
        val listeners = synchronized(lock) {
            loadingJobs.remove(videoUrl)
            callbacks.remove(videoUrl).orEmpty()
        }
        mainHandler.post {
            listeners.forEach { listener -> listener(bitmap) }
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap

        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun clearCache() {
        val jobs = synchronized(lock) {
            val activeJobs = loadingJobs.values.toList()
            loadingJobs.clear()
            callbacks.clear()
            activeJobs
        }
        jobs.forEach(Job::cancel)
        bitmapCache.evictAll()
    }

    fun release() {
        clearCache()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
