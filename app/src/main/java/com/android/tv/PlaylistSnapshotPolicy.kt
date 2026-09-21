package com.android.tv

/** Pure rules for deciding when the last usable playlist snapshot may be used. */
internal object PlaylistSnapshotPolicy {
    fun containsPlayableChannel(groups: Map<String, List<Movie>>): Boolean {
        return groups.values.asSequence()
            .flatten()
            .any { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
    }

    fun shouldUseSnapshot(
        currentSourceUrl: String,
        snapshotSourceUrl: String?,
        snapshotGroups: Map<String, List<Movie>>
    ): Boolean {
        return currentSourceUrl == snapshotSourceUrl && containsPlayableChannel(snapshotGroups)
    }
}
