package com.android.tv

/** Admits one viewport scroll notification per rendered frame. */
internal class ViewportScrollFrameGate {
    private var notificationPending = false

    fun tryDispatch(): Boolean {
        if (notificationPending) return false
        notificationPending = true
        return true
    }

    fun onNextFrame() {
        notificationPending = false
    }

    fun reset() {
        notificationPending = false
    }
}

/** Emits visibility changes once, then allows a new listener to take a fresh snapshot. */
internal class VisibilityChangeGate {
    private var lastVisibility: Boolean? = null

    fun shouldDispatch(isVisible: Boolean): Boolean {
        if (lastVisibility == isVisible) return false
        lastVisibility = isVisible
        return true
    }

    fun reset() {
        lastVisibility = null
    }
}

internal data class BackgroundUpdateRequest(
    val uri: String?,
    val generation: Long
)

/** Deduplicates repeated selections and fences results from superseded loads. */
internal class BackgroundUpdateGate {
    private var selectedUri: String? = null
    private var generation = 0L

    fun select(uri: String?): BackgroundUpdateRequest? {
        val normalizedUri = uri?.takeUnless(String::isBlank)
        if (selectedUri == normalizedUri) return null

        selectedUri = normalizedUri
        generation += 1L
        return BackgroundUpdateRequest(normalizedUri, generation)
    }

    fun reset() {
        selectedUri = null
        generation += 1L
    }

    fun isCurrent(request: BackgroundUpdateRequest): Boolean {
        return request.generation == generation && request.uri == selectedUri
    }
}
