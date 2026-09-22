package com.android.tv

/** A live card that is eligible for a static frame request. */
internal data class VisibleLivePreviewCandidate(
    val requestKey: String,
    val videoUrl: String,
    val isLive: Boolean,
    val isVisible: Boolean,
    val isFocused: Boolean
)

/** Pure eligibility rules shared by the visible-frame controller and JVM tests. */
internal object VisibleLivePreviewPolicy {
    fun canRequest(candidate: VisibleLivePreviewCandidate, cached: Boolean): Boolean {
        return !cached &&
            candidate.requestKey.isNotBlank() &&
            candidate.isLive &&
            candidate.videoUrl.isNotBlank() &&
            candidate.isVisible &&
            !candidate.isFocused
    }
}

/**
 * FIFO queue keyed by video URL. Repeated visibility/layout notifications and
 * duplicate row occurrences do not create duplicate stream starts.
 */
internal class VisibleLivePreviewRequestQueue {
    private val requests = LinkedHashMap<String, VisibleLivePreviewRequest>()

    fun enqueue(videoUrl: String): Boolean {
        if (videoUrl.isBlank()) return false
        if (requests.containsKey(videoUrl)) return false
        requests[videoUrl] = VisibleLivePreviewRequest(videoUrl)
        return true
    }

    fun remove(videoUrl: String): Boolean = requests.remove(videoUrl) != null

    fun poll(): VisibleLivePreviewRequest? {
        val iterator = requests.entries.iterator()
        if (!iterator.hasNext()) return null
        val request = iterator.next().value
        iterator.remove()
        return request
    }

    fun removeIf(predicate: (VisibleLivePreviewRequest) -> Boolean) {
        val iterator = requests.entries.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next().value)) iterator.remove()
        }
    }

    fun clear() = requests.clear()

    fun contains(videoUrl: String): Boolean = requests.containsKey(videoUrl)

    val size: Int
        get() = requests.size
}

internal data class VisibleLivePreviewRequest(
    val videoUrl: String
)
