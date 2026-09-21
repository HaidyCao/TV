package com.android.tv

import android.graphics.Bitmap

/**
 * Keeps the last rendered frame for live channels so an inactive card can
 * remain visually stable without opening another stream.
 *
 * The backing cache is byte bounded rather than entry bounded. A frame is
 * retained only when its URL is non-empty, its bitmap is usable, and it fits
 * within the total budget by itself.
 */
class LivePreviewFrameStore(
    maxBytes: Int = DEFAULT_MAX_BYTES
) {
    private val frames = ByteBoundedLruCache<String, Bitmap>(maxBytes) { bitmap ->
        bitmap.allocationByteCount
    }

    fun get(videoUrl: String): Bitmap? {
        if (videoUrl.isBlank()) return null
        val bitmap = frames.get(videoUrl) ?: return null
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            frames.remove(videoUrl)
            return null
        }
        return bitmap
    }

    /** Returns true when the frame remains in the cache after insertion. */
    fun put(videoUrl: String, bitmap: Bitmap): Boolean {
        if (videoUrl.isBlank() || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return false
        }
        return frames.put(videoUrl, bitmap)
    }

    fun clear() {
        frames.clear()
    }

    companion object {
        // Four 313x176 ARGB frames are roughly 0.9 MiB; this leaves room for
        // several recently visited channels without allowing unbounded growth.
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    }
}

/** Small access ordered cache whose size is measured in caller supplied bytes. */
internal class ByteBoundedLruCache<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int
) {
    private val entries = LinkedHashMap<K, Entry<V>>(0, 0.75f, true)
    private var currentBytes = 0

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Synchronized
    fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V): Boolean {
        val valueBytes = sizeOf(value).coerceAtLeast(0)
        entries.remove(key)?.let { currentBytes -= it.bytes }
        if (valueBytes == 0 || valueBytes > maxBytes) return false

        entries[key] = Entry(value, valueBytes)
        currentBytes += valueBytes
        trimToSize()
        return entries.containsKey(key)
    }

    @Synchronized
    fun remove(key: K) {
        entries.remove(key)?.let { currentBytes -= it.bytes }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0
    }

    internal val sizeBytes: Int
        @Synchronized get() = currentBytes

    private fun trimToSize() {
        while (currentBytes > maxBytes && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            currentBytes -= eldest.value.bytes
            entries.remove(eldest.key)
        }
    }

    private data class Entry<V>(
        val value: V,
        val bytes: Int
    )
}
