package com.android.tv

import java.security.MessageDigest
import java.util.Locale

/** One TV playback history entry. Stream URLs are represented only by a one-way fingerprint. */
internal data class RecentWatchRecord(
    val sourceUrl: String,
    val channelId: Long,
    val watchedAtMillis: Long,
    val duplicateGroupKey: String,
    val videoUrlFingerprint: String,
    val duplicateCountAtWatch: Int
)

internal data class ResolvedRecentWatch(
    val record: RecentWatchRecord,
    val channel: Movie
)

/** Pure rules for recording, retaining, and safely resolving recently watched channels. */
internal object RecentWatchPolicy {
    const val MAX_RECORDS = 20

    fun shouldRecord(
        playbackState: PlaybackUiState,
        playerIsPlaying: Boolean,
        requestStillCurrent: Boolean,
        channelStillCurrent: Boolean,
        playlistSourceStillCurrent: Boolean
    ): Boolean = playbackState == PlaybackUiState.PLAYING &&
        playerIsPlaying &&
        requestStillCurrent &&
        channelStillCurrent &&
        playlistSourceStillCurrent

    /** Returns null when the playing item cannot be tied unambiguously to the active playlist. */
    fun record(
        existing: List<RecentWatchRecord>,
        sourceUrl: String,
        groupsSourceUrl: String?,
        groups: Map<String, List<Movie>>,
        playingChannel: Movie,
        watchedAtMillis: Long
    ): List<RecentWatchRecord>? {
        if (sourceUrl.isBlank() || groupsSourceUrl != sourceUrl || watchedAtMillis <= 0L) return null
        if (!isPlayableLive(playingChannel) || playingChannel.id == 0L) return null

        val channels = playableChannels(groups)
        val currentMatches = channels.filter { it.id == playingChannel.id }
        if (currentMatches.size != 1) return null
        val currentChannel = currentMatches.single()
        // Do not record an old card URL against a new playlist entry with the same stable ID.
        if (currentChannel.videoUrl != playingChannel.videoUrl) return null

        val identity = duplicateGroupKey(currentChannel)
        val duplicateCount = channels.count { duplicateGroupKey(it) == identity }.coerceAtLeast(1)
        val next = RecentWatchRecord(
            sourceUrl = sourceUrl,
            channelId = currentChannel.id,
            watchedAtMillis = watchedAtMillis,
            duplicateGroupKey = identity,
            videoUrlFingerprint = fingerprint(currentChannel.videoUrl.orEmpty()),
            duplicateCountAtWatch = duplicateCount
        )
        return normalize(existing + next)
    }

    /** Resolves history only through the current source's current playlist objects. */
    fun resolve(
        records: List<RecentWatchRecord>,
        sourceUrl: String,
        groupsSourceUrl: String?,
        groups: Map<String, List<Movie>>
    ): List<ResolvedRecentWatch> {
        if (sourceUrl.isBlank() || groupsSourceUrl != sourceUrl) return emptyList()

        val currentChannels = playableChannels(groups)
        val channelsById = currentChannels.groupBy(Movie::id)
        val channelsByIdentity = currentChannels.groupBy(::duplicateGroupKey)
        val resolvedIds = mutableSetOf<Long>()
        return normalize(records)
            .asSequence()
            .filter { it.sourceUrl == sourceUrl }
            .mapNotNull { record ->
                val candidates = channelsById[record.channelId].orEmpty()
                if (candidates.size != 1) return@mapNotNull null
                val channel = candidates.single()
                val currentDuplicateCount = channelsByIdentity[record.duplicateGroupKey].orEmpty().size
                val wasOrIsAmbiguous = record.duplicateCountAtWatch > 1 || currentDuplicateCount > 1
                if (wasOrIsAmbiguous &&
                    fingerprint(channel.videoUrl.orEmpty()) != record.videoUrlFingerprint
                ) return@mapNotNull null
                if (!resolvedIds.add(channel.id)) return@mapNotNull null
                ResolvedRecentWatch(record, channel)
            }
            .take(MAX_RECORDS)
            .toList()
    }

    fun normalize(records: List<RecentWatchRecord>): List<RecentWatchRecord> {
        val latestByChannel = LinkedHashMap<Pair<String, Long>, RecentWatchRecord>()
        records.forEach { record ->
            if (!isValid(record)) return@forEach
            val key = record.sourceUrl to record.channelId
            val existing = latestByChannel[key]
            if (existing == null || record.watchedAtMillis > existing.watchedAtMillis) {
                latestByChannel[key] = record
            }
        }
        return latestByChannel.values
            .sortedWith(compareByDescending<RecentWatchRecord> { it.watchedAtMillis }
                .thenBy { it.sourceUrl }
                .thenBy { it.channelId })
            .take(MAX_RECORDS)
    }

    internal fun duplicateGroupKey(channel: Movie): String = channel.playlistIdentity
        ?.takeIf(String::isNotBlank)
        ?.let { "identity:$it" }
        ?: listOf(
            normalizeIdentity(channel.category.orEmpty()),
            normalizeIdentity(channel.title.orEmpty())
        ).joinToString("\u001f")

    internal fun fingerprint(videoUrl: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(videoUrl.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun normalizeIdentity(value: String): String = value
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private fun playableChannels(groups: Map<String, List<Movie>>): List<Movie> =
        groups.values.flatten().filter(::isPlayableLive)

    private fun isPlayableLive(channel: Movie): Boolean =
        channel.isLive && !channel.videoUrl.isNullOrBlank()

    private fun isValid(record: RecentWatchRecord): Boolean =
        record.sourceUrl.isNotBlank() &&
            record.channelId != 0L &&
            record.watchedAtMillis > 0L &&
            record.duplicateCountAtWatch > 0 &&
            record.videoUrlFingerprint.matches(Regex("[0-9a-f]{64}"))
}
