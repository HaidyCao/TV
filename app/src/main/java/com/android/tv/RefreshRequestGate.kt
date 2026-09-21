package com.android.tv

/** Identifies the newest refresh so late results cannot publish after it. */
internal class RefreshRequestGate {
    private var newestRequestId = 0L

    @Synchronized
    fun begin(): Long {
        newestRequestId += 1
        return newestRequestId
    }

    @Synchronized
    fun isCurrent(requestId: Long): Boolean {
        return requestId == newestRequestId
    }

    @Synchronized
    fun runIfCurrent(requestId: Long, action: () -> Unit): Boolean {
        if (requestId != newestRequestId) return false
        action()
        return true
    }
}
