package com.android.tv

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Stores TV playback history without retaining or replaying historical stream URLs. */
internal object RecentWatchRepository {
    private const val PREFERENCES_NAME = "tv_recent_watches"
    private const val KEY_RECORDS = "records_v1"
    private const val FIELD_SEPARATOR = '\t'

    @Synchronized
    fun records(context: Context): List<RecentWatchRecord> {
        val encoded = readPreferences(context).getStringSet(KEY_RECORDS, emptySet())
            .orEmpty()
            .toList()
        return RecentWatchPolicy.normalize(encoded.mapNotNull(::decode))
    }

    @Synchronized
    fun recordPlayingChannel(
        context: Context,
        sourceUrl: String,
        groupsSourceUrl: String?,
        groups: Map<String, List<Movie>>,
        playingChannel: Movie,
        watchedAtMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val updated = RecentWatchPolicy.record(
            existing = records(context),
            sourceUrl = sourceUrl,
            groupsSourceUrl = groupsSourceUrl,
            groups = groups,
            playingChannel = playingChannel,
            watchedAtMillis = watchedAtMillis
        ) ?: return false
        return TvDataManager.writeIfCurrentSource(context, sourceUrl) {
            readPreferences(context).edit()
                .putStringSet(KEY_RECORDS, updated.map(::encode).toSet())
                .commit()
        }
    }

    fun resolve(
        context: Context,
        sourceUrl: String,
        groupsSourceUrl: String?,
        groups: Map<String, List<Movie>>
    ): List<ResolvedRecentWatch> = RecentWatchPolicy.resolve(
        records = records(context),
        sourceUrl = sourceUrl,
        groupsSourceUrl = groupsSourceUrl,
        groups = groups
    )

    @Synchronized
    fun clear(context: Context): Boolean = readPreferences(context).edit()
        .remove(KEY_RECORDS)
        .commit()

    private fun readPreferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun encode(record: RecentWatchRecord): String = listOf(
        encodeField(record.sourceUrl),
        record.channelId.toString(),
        record.watchedAtMillis.toString(),
        encodeField(record.duplicateGroupKey),
        record.videoUrlFingerprint,
        record.duplicateCountAtWatch.toString()
    ).joinToString(FIELD_SEPARATOR.toString())

    private fun decode(value: String): RecentWatchRecord? = runCatching {
        val parts = value.split(FIELD_SEPARATOR)
        if (parts.size != 6) return null
        RecentWatchRecord(
            sourceUrl = decodeField(parts[0]),
            channelId = parts[1].toLong(),
            watchedAtMillis = parts[2].toLong(),
            duplicateGroupKey = decodeField(parts[3]),
            videoUrlFingerprint = parts[4],
            duplicateCountAtWatch = parts[5].toInt()
        ).takeIf { record ->
            record.sourceUrl.isNotBlank() &&
                record.channelId != 0L &&
                record.watchedAtMillis > 0L &&
                record.duplicateCountAtWatch > 0 &&
                record.videoUrlFingerprint.matches(Regex("[0-9a-f]{64}"))
        }
    }.getOrNull()

    private fun encodeField(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeField(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
