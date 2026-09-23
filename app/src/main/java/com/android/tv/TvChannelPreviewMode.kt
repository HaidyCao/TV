package com.android.tv

/** Preview cost choices shown in TV settings. */
enum class TvChannelPreviewMode(val preferenceValue: String) {
    OFF("off"),
    FOCUSED_ONLY("focused_only"),
    FOCUSED_AND_VISIBLE("focused_and_visible");

    companion object {
        internal fun fromPreferenceValue(value: String?): TvChannelPreviewMode? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

internal enum class LivePreviewFrameOrigin {
    FOCUSED_STREAM,
    VISIBLE_STATIC_CAPTURE
}

/** Pure migration and display rules for TV preview state. */
internal object TvChannelPreviewModePolicy {
    fun fromStoredOrLegacy(
        storedMode: String?,
        legacyEnabled: Boolean?
    ): TvChannelPreviewMode {
        return TvChannelPreviewMode.fromPreferenceValue(storedMode)
            ?: if (legacyEnabled == false) {
                TvChannelPreviewMode.OFF
            } else {
                // A missing legacy key historically defaulted to enabled.
                TvChannelPreviewMode.FOCUSED_AND_VISIBLE
            }
    }

    fun allowsFocusedStream(mode: TvChannelPreviewMode): Boolean =
        mode != TvChannelPreviewMode.OFF

    fun allowsStaticCapture(mode: TvChannelPreviewMode): Boolean =
        mode == TvChannelPreviewMode.FOCUSED_AND_VISIBLE

    fun canDisplayFrame(
        mode: TvChannelPreviewMode,
        origin: LivePreviewFrameOrigin
    ): Boolean = when (mode) {
        TvChannelPreviewMode.OFF -> false
        TvChannelPreviewMode.FOCUSED_ONLY -> origin == LivePreviewFrameOrigin.FOCUSED_STREAM
        TvChannelPreviewMode.FOCUSED_AND_VISIBLE -> true
    }
}

/** Small pure counter used by both preview controllers; it never stores stream URLs. */
internal class PreviewTelemetryTracker {
    private var nextToken = 0L
    private val activeStarts = linkedMapOf<Long, ActiveStart>()
    private var focusedStarts = 0
    private var staticStarts = 0
    private var staticStartsDuringScroll = 0
    private var focusedFirstFrames = 0
    private var staticFirstFrames = 0
    private var lastFocusedFirstFrameMs: Long? = null
    private var lastStaticFirstFrameMs: Long? = null
    private var peakConcurrentPlayers = 0

    @Synchronized
    fun started(kind: PreviewStreamKind, nowMs: Long, duringScroll: Boolean = false): Long {
        val token = ++nextToken
        activeStarts[token] = ActiveStart(kind, nowMs)
        when (kind) {
            PreviewStreamKind.FOCUSED -> focusedStarts += 1
            PreviewStreamKind.STATIC -> {
                staticStarts += 1
                if (duringScroll) staticStartsDuringScroll += 1
            }
        }
        peakConcurrentPlayers = maxOf(peakConcurrentPlayers, activeStarts.size)
        return token
    }

    @Synchronized
    fun firstFrame(token: Long, nowMs: Long): PreviewTelemetryFirstFrame? {
        val started = activeStarts[token] ?: return null
        if (started.firstFrameReported) return null
        started.firstFrameReported = true
        val latency = (nowMs - started.startedAtMs).coerceAtLeast(0L)
        when (started.kind) {
            PreviewStreamKind.FOCUSED -> {
                focusedFirstFrames += 1
                lastFocusedFirstFrameMs = latency
            }
            PreviewStreamKind.STATIC -> {
                staticFirstFrames += 1
                lastStaticFirstFrameMs = latency
            }
        }
        return PreviewTelemetryFirstFrame(started.kind, latency, snapshot())
    }

    @Synchronized
    fun stopped(token: Long) {
        activeStarts.remove(token)
    }

    @Synchronized
    fun snapshot(): PreviewTelemetrySnapshot = PreviewTelemetrySnapshot(
        focusedStarts = focusedStarts,
        staticStarts = staticStarts,
        staticStartsDuringScroll = staticStartsDuringScroll,
        focusedFirstFrames = focusedFirstFrames,
        staticFirstFrames = staticFirstFrames,
        lastFocusedFirstFrameMs = lastFocusedFirstFrameMs,
        lastStaticFirstFrameMs = lastStaticFirstFrameMs,
        activePlayers = activeStarts.size,
        peakConcurrentPlayers = peakConcurrentPlayers
    )

    private data class ActiveStart(
        val kind: PreviewStreamKind,
        val startedAtMs: Long,
        var firstFrameReported: Boolean = false
    )
}

internal enum class PreviewStreamKind { FOCUSED, STATIC }

internal data class PreviewTelemetryFirstFrame(
    val kind: PreviewStreamKind,
    val latencyMs: Long,
    val snapshot: PreviewTelemetrySnapshot
)

internal data class PreviewTelemetrySnapshot(
    val focusedStarts: Int,
    val staticStarts: Int,
    val staticStartsDuringScroll: Int,
    val focusedFirstFrames: Int,
    val staticFirstFrames: Int,
    val lastFocusedFirstFrameMs: Long?,
    val lastStaticFirstFrameMs: Long?,
    val activePlayers: Int,
    val peakConcurrentPlayers: Int
)

/** Coalesces repeated layout/visibility callbacks without moving a settle deadline. */
internal object VisiblePreviewSettlePolicy {
    fun initialDeadline(existingDeadlineMs: Long?, nowMs: Long, quietPeriodMs: Long): Long =
        existingDeadlineMs ?: (nowMs + quietPeriodMs)

    fun scrollDeadline(nowMs: Long, quietPeriodMs: Long): Long = nowMs + quietPeriodMs

    fun focusReleaseDeadline(
        nowMs: Long,
        existingDeadlineMs: Long?,
        quietPeriodMs: Long
    ): Long = maxOf(existingDeadlineMs ?: Long.MIN_VALUE, nowMs + quietPeriodMs)

    fun remainingDelay(nowMs: Long, deadlineMs: Long?): Long =
        deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L
}
