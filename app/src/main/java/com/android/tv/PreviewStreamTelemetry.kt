package com.android.tv

import android.os.SystemClock
import android.util.Log

/** Debug-only aggregate preview counters. Stream URLs and credentials are never logged. */
internal object PreviewStreamTelemetry {
    private const val TAG = "PreviewTelemetry"
    private val tracker = PreviewTelemetryTracker()

    fun started(kind: PreviewStreamKind, duringScroll: Boolean = false): Long {
        val token = tracker.started(kind, SystemClock.elapsedRealtime(), duringScroll)
        logSnapshot("start_${kind.name.lowercase()}")
        return token
    }

    fun firstFrame(token: Long) {
        val event = tracker.firstFrame(token, SystemClock.elapsedRealtime()) ?: return
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "first_frame_${event.kind.name.lowercase()} latency_ms=${event.latencyMs} " +
                    format(event.snapshot)
            )
        }
    }

    fun stopped(token: Long) {
        tracker.stopped(token)
        logSnapshot("stop")
    }

    /** Inspectable aggregate counters for local debugging; contains no URLs. */
    fun snapshot(): PreviewTelemetrySnapshot = tracker.snapshot()

    private fun logSnapshot(event: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "$event ${format(tracker.snapshot())}")
    }

    private fun format(snapshot: PreviewTelemetrySnapshot): String =
        "focus_starts=${snapshot.focusedStarts} " +
            "static_starts=${snapshot.staticStarts} " +
            "static_starts_during_scroll=${snapshot.staticStartsDuringScroll} " +
            "focus_first_frames=${snapshot.focusedFirstFrames} " +
            "static_first_frames=${snapshot.staticFirstFrames} " +
            "active=${snapshot.activePlayers} peak=${snapshot.peakConcurrentPlayers}"
}
