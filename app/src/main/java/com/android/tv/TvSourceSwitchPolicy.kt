package com.android.tv

/** A parsed, playable playlist that can be activated or kept for local recovery. */
internal data class TvSourceSnapshot(
    val sourceUrl: String,
    val playlist: String,
    val updatedAtMillis: Long,
    val channelCount: Int,
    val groupCount: Int
)

/** Persisted source selection after validating the active and previous snapshots. */
internal data class TvSourceStoredState(
    val activeSourceUrl: String,
    val activeSnapshot: TvSourceSnapshot?,
    val previousSnapshot: TvSourceSnapshot?
)

/** Converts the legacy single-source keys into the new in-memory source state. */
internal object TvSourceStateMigrationPolicy {
    fun fromLegacy(
        activeSourceUrl: String,
        legacySnapshot: TvSourceSnapshot?
    ): TvSourceStoredState = TvSourceStoredState(
        activeSourceUrl = activeSourceUrl,
        activeSnapshot = legacySnapshot?.takeIf {
            TvSourceSwitchPolicy.isUsable(it) && it.sourceUrl == activeSourceUrl
        },
        previousSnapshot = null
    )
}

/** Pure state changes for safe source activation and local fallback. */
internal object TvSourceSwitchPolicy {

    fun activate(
        current: TvSourceStoredState,
        candidate: TvSourceSnapshot?
    ): TvSourceStoredState {
        candidate ?: return current
        if (!isUsable(candidate)) return current

        val previous = if (candidate.sourceUrl == current.activeSourceUrl) {
            usablePrevious(current, candidate.sourceUrl)
        } else {
            usableActive(current) ?: usablePrevious(current, candidate.sourceUrl)
        }
        return TvSourceStoredState(
            activeSourceUrl = candidate.sourceUrl,
            activeSnapshot = candidate,
            previousSnapshot = previous
        )
    }

    /** Restoring swaps the usable snapshots, so the source just left remains a valid next fallback. */
    fun restore(current: TvSourceStoredState): TvSourceStoredState? {
        val previous = usablePrevious(current, current.activeSourceUrl) ?: return null
        val currentSnapshot = usableActive(current)
        return TvSourceStoredState(
            activeSourceUrl = previous.sourceUrl,
            activeSnapshot = previous,
            previousSnapshot = currentSnapshot
        )
    }

    fun previousSnapshot(current: TvSourceStoredState): TvSourceSnapshot? =
        usablePrevious(current, current.activeSourceUrl)

    fun isUsable(snapshot: TvSourceSnapshot): Boolean =
        snapshot.sourceUrl.isNotBlank() &&
            snapshot.playlist.isNotBlank() &&
            snapshot.updatedAtMillis > 0L &&
            snapshot.channelCount > 0 &&
            snapshot.groupCount > 0

    private fun usableActive(current: TvSourceStoredState): TvSourceSnapshot? =
        current.activeSnapshot?.takeIf {
            it.sourceUrl == current.activeSourceUrl && isUsable(it)
        }

    private fun usablePrevious(
        current: TvSourceStoredState,
        activeSourceUrl: String
    ): TvSourceSnapshot? = current.previousSnapshot?.takeIf {
        it.sourceUrl != activeSourceUrl && isUsable(it)
    }
}
