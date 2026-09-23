package com.android.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TvChannelFetchResult(
    val groups: Map<String, List<Movie>>,
    val sourceUrl: String,
    val fromSnapshot: Boolean,
    val updatedAtMillis: Long
)

data class TvSourceCheckResult(
    val channelCount: Int,
    val groupCount: Int
)

data class TvCachedSourceInfo(
    val channelCount: Int,
    val groupCount: Int,
    val updatedAtMillis: Long
)

data class TvAvailableSourceInfo(
    val sourceUrl: String,
    val channelCount: Int,
    val groupCount: Int,
    val updatedAtMillis: Long
)

internal data class TvPreparedSource(
    val snapshot: TvSourceSnapshot,
    val groups: Map<String, List<Movie>>
)

data class TvSourceActivationResult(
    val fetchResult: TvChannelFetchResult,
    val previousSource: TvAvailableSourceInfo?
)

object TvDataManager {
    const val DEFAULT_TV_LIST_URL = "https://raw.githubusercontent.com/HaidyCao/configs/refs/heads/main/tvlist.txt"

    private const val PREFS_NAME = "tv_prefs"
    private const val KEY_SOURCE_URL = "tv_source_url"
    private const val KEY_CACHED_SOURCE_URL = "cached_tv_source_url"
    private const val KEY_CACHED_PLAYLIST = "cached_tv_playlist"
    private const val KEY_CACHED_AT = "cached_tv_playlist_at"
    private const val KEY_PREVIOUS_SOURCE_URL = "previous_tv_source_url"
    private const val KEY_PREVIOUS_PLAYLIST = "previous_cached_tv_playlist"
    private const val KEY_PREVIOUS_AT = "previous_cached_tv_playlist_at"
    private const val MAX_PLAYLIST_CHARS = 512 * 1024
    private const val HTTP_CACHE_BYTES = 5L * 1024L * 1024L
    private const val DEFAULT_GROUP = "其他频道"

    private val logoPattern = Regex("""tvg-logo="([^"]+)""", RegexOption.IGNORE_CASE)
    private val tvgIdPattern = Regex("""tvg-id="([^"]*)""", RegexOption.IGNORE_CASE)
    private val groupPattern = Regex("""group-title="([^"]+)""", RegexOption.IGNORE_CASE)

    @Volatile
    private var httpClient: OkHttpClient? = null
    private val fetchGate = RefreshRequestGate()
    /** Serializes source preference changes with playlist snapshot writes. */
    private val sourceStateLock = Any()
    private var sourceGeneration = 0L

    private data class RawSourcePreferences(val values: Map<String, Any?>)

    fun getSourceUrl(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_URL, DEFAULT_TV_LIST_URL)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_TV_LIST_URL }
    }

    /** Captured when a repository refresh is requested to reject jobs delayed across a switch. */
    internal fun currentSourceGeneration(): Long = synchronized(sourceStateLock) { sourceGeneration }

    fun isValidSourceUrl(sourceUrl: String): Boolean {
        val uri = runCatching { Uri.parse(sourceUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    /**
     * Checks a candidate source without changing the saved source or snapshot.
     * It deliberately does not use fetchTvChannels or the refresh gate.
     */
    suspend fun checkSource(
        context: Context,
        candidateUrl: String
    ): TvSourceCheckResult {
        val prepared = prepareSource(context, candidateUrl)
        return TvSourceCheckResult(
            channelCount = prepared.snapshot.channelCount,
            groupCount = prepared.snapshot.groupCount
        )
    }

    /** Downloads and parses a candidate without changing the selected source or its snapshots. */
    internal suspend fun prepareSource(
        context: Context,
        candidateUrl: String
    ): TvPreparedSource {
        val sourceUrl = candidateUrl.trim()
        require(isValidSourceUrl(sourceUrl)) { "节目源地址必须是有效的 HTTP 或 HTTPS URL" }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(sourceUrl)
                .header("Accept", "application/x-mpegurl, audio/x-mpegurl, text/plain, */*")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            val content = executePlaylistRequest(client(context.applicationContext), request)
            val groups = parsePlaylist(content)
            val playableGroups = groups.filterValues { channels ->
                channels.any { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
            }
            if (playableGroups.isEmpty()) {
                throw IOException("节目单为空或没有可播放频道")
            }
            val now = System.currentTimeMillis()
            val snapshot = TvSourceSnapshot(
                sourceUrl = sourceUrl,
                playlist = content,
                updatedAtMillis = now,
                channelCount = playableGroups.values.sumOf { channels ->
                    channels.count { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
                },
                groupCount = playableGroups.size
            )
            TvPreparedSource(snapshot, groups)
        }
    }

    /** Reads only the usable snapshot belonging to the currently saved source. */
    suspend fun readCachedSourceInfo(context: Context): TvCachedSourceInfo? {
        return withContext(Dispatchers.IO) {
            readStoredSourceState(context.applicationContext).activeSnapshot?.toCachedInfo()
        }
    }

    /** Returns only a previous source with a parsed, playable local snapshot. */
    suspend fun readPreviousSourceInfo(context: Context): TvAvailableSourceInfo? {
        return withContext(Dispatchers.IO) {
            val state = readStoredSourceState(context.applicationContext)
            TvSourceSwitchPolicy.previousSnapshot(state)?.toAvailableInfo()
        }
    }

    /** Persists an already fetched and validated candidate as the new current source. */
    internal fun activatePreparedSource(
        context: Context,
        prepared: TvPreparedSource
    ): TvSourceActivationResult {
        if (!TvSourceSwitchPolicy.isUsable(prepared.snapshot)) {
            throw IOException("候选节目单没有可用的直播频道")
        }
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return synchronized(sourceStateLock) {
            // Invalidate both old fetches before reading their final cache state.
            fetchGate.begin()
            sourceGeneration += 1L
            val oldValues = captureSourcePreferences(prefs)
            val current = readStoredSourceState(appContext)
            val next = TvSourceSwitchPolicy.activate(current, prepared.snapshot)
            if (next.activeSnapshot != prepared.snapshot) {
                throw IOException("候选节目单无法激活")
            }

            val editor = prefs.edit().putString(KEY_SOURCE_URL, next.activeSourceUrl)
            putSnapshot(editor, KEY_CACHED_SOURCE_URL, KEY_CACHED_PLAYLIST, KEY_CACHED_AT, next.activeSnapshot)
            putSnapshot(
                editor,
                KEY_PREVIOUS_SOURCE_URL,
                KEY_PREVIOUS_PLAYLIST,
                KEY_PREVIOUS_AT,
                next.previousSnapshot
            )
            if (!editor.commit()) {
                restoreSourcePreferences(prefs, oldValues)
                throw IOException("无法保存节目源设置")
            }
            TvSourceActivationResult(
                fetchResult = TvChannelFetchResult(
                    groups = prepared.groups,
                    sourceUrl = prepared.snapshot.sourceUrl,
                    fromSnapshot = false,
                    updatedAtMillis = prepared.snapshot.updatedAtMillis
                ),
                previousSource = next.previousSnapshot?.toAvailableInfo()
            )
        }
    }

    /** Activates a valid stored fallback and swaps the current source into the fallback slot. */
    internal fun restorePreviousSource(context: Context): TvSourceActivationResult? {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return synchronized(sourceStateLock) {
            val current = readStoredSourceState(appContext)
            val next = TvSourceSwitchPolicy.restore(current) ?: return@synchronized null
            val activeSnapshot = next.activeSnapshot ?: return@synchronized null
            val groups = parsePlaylist(activeSnapshot.playlist)
            if (!PlaylistSnapshotPolicy.containsPlayableChannel(groups)) return@synchronized null

            fetchGate.begin()
            sourceGeneration += 1L
            val oldValues = captureSourcePreferences(prefs)
            val editor = prefs.edit().putString(KEY_SOURCE_URL, next.activeSourceUrl)
            putSnapshot(editor, KEY_CACHED_SOURCE_URL, KEY_CACHED_PLAYLIST, KEY_CACHED_AT, activeSnapshot)
            putSnapshot(
                editor,
                KEY_PREVIOUS_SOURCE_URL,
                KEY_PREVIOUS_PLAYLIST,
                KEY_PREVIOUS_AT,
                next.previousSnapshot
            )
            if (!editor.commit()) {
                restoreSourcePreferences(prefs, oldValues)
                throw IOException("无法恢复上一个节目源")
            }
            TvSourceActivationResult(
                fetchResult = TvChannelFetchResult(
                    groups = groups,
                    sourceUrl = activeSnapshot.sourceUrl,
                    fromSnapshot = true,
                    updatedAtMillis = activeSnapshot.updatedAtMillis
                ),
                previousSource = next.previousSnapshot?.toAvailableInfo()
            )
        }
    }

    private fun readStoredSourceState(context: Context): TvSourceStoredState = synchronized(sourceStateLock) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeSourceUrl = getSourceUrl(context)
        val legacySnapshot = readSnapshot(
            prefs,
            KEY_CACHED_SOURCE_URL,
            KEY_CACHED_PLAYLIST,
            KEY_CACHED_AT
        )
        val previousSnapshot = readSnapshot(
            prefs,
            KEY_PREVIOUS_SOURCE_URL,
            KEY_PREVIOUS_PLAYLIST,
            KEY_PREVIOUS_AT
        )?.takeIf { it.sourceUrl != activeSourceUrl }
        TvSourceStateMigrationPolicy.fromLegacy(activeSourceUrl, legacySnapshot)
            .copy(previousSnapshot = previousSnapshot)
    }

    private fun readSnapshot(
        prefs: SharedPreferences,
        sourceKey: String,
        playlistKey: String,
        timestampKey: String
    ): TvSourceSnapshot? {
        val sourceUrl = prefs.getString(sourceKey, null) ?: return null
        val playlist = prefs.getString(playlistKey, null) ?: return null
        val updatedAtMillis = prefs.getLong(timestampKey, 0L)
        if (!isValidSourceUrl(sourceUrl) || updatedAtMillis <= 0L || playlist.isBlank()) return null

        val groups = runCatching { parsePlaylist(playlist) }.getOrNull() ?: return null
        val playableGroups = groups.filterValues { channels ->
            channels.any { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
        }
        if (playableGroups.isEmpty()) return null
        val snapshot = TvSourceSnapshot(
            sourceUrl = sourceUrl,
            playlist = playlist,
            updatedAtMillis = updatedAtMillis,
            channelCount = playableGroups.values.sumOf { channels ->
                channels.count { channel -> channel.isLive && !channel.videoUrl.isNullOrBlank() }
            },
            groupCount = playableGroups.size
        )
        return snapshot.takeIf(TvSourceSwitchPolicy::isUsable)
    }

    private fun putSnapshot(
        editor: SharedPreferences.Editor,
        sourceKey: String,
        playlistKey: String,
        timestampKey: String,
        snapshot: TvSourceSnapshot?
    ) {
        if (snapshot == null) {
            editor.remove(sourceKey).remove(playlistKey).remove(timestampKey)
        } else {
            editor.putString(sourceKey, snapshot.sourceUrl)
                .putString(playlistKey, snapshot.playlist)
                .putLong(timestampKey, snapshot.updatedAtMillis)
        }
    }

    private fun captureSourcePreferences(prefs: SharedPreferences): RawSourcePreferences {
        return RawSourcePreferences(
            listOf(
                KEY_SOURCE_URL,
                KEY_CACHED_SOURCE_URL,
                KEY_CACHED_PLAYLIST,
                KEY_CACHED_AT,
                KEY_PREVIOUS_SOURCE_URL,
                KEY_PREVIOUS_PLAYLIST,
                KEY_PREVIOUS_AT
            ).associateWith { key -> if (prefs.contains(key)) prefs.all[key] else null }
        )
    }

    private fun restoreSourcePreferences(
        prefs: SharedPreferences,
        oldValues: RawSourcePreferences
    ) {
        val editor = prefs.edit()
        oldValues.values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                null -> editor.remove(key)
            }
        }
        editor.commit()
    }

    private fun TvSourceSnapshot.toCachedInfo() = TvCachedSourceInfo(
        channelCount = channelCount,
        groupCount = groupCount,
        updatedAtMillis = updatedAtMillis
    )

    private fun TvSourceSnapshot.toAvailableInfo() = TvAvailableSourceInfo(
        sourceUrl = sourceUrl,
        channelCount = channelCount,
        groupCount = groupCount,
        updatedAtMillis = updatedAtMillis
    )

    suspend fun fetchTvChannels(
        context: Context,
        forceNetwork: Boolean = false,
        expectedSourceGeneration: Long? = null
    ): TvChannelFetchResult {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val (requestId, sourceUrl) = synchronized(sourceStateLock) {
                if (expectedSourceGeneration != null && expectedSourceGeneration != sourceGeneration) {
                    throw CancellationException("节目源已切换，忽略旧刷新请求")
                }
                val id = fetchGate.begin()
                val currentUrl = getSourceUrl(appContext)
                require(isValidSourceUrl(currentUrl)) { "节目源地址必须是有效的 HTTP 或 HTTPS URL" }
                id to currentUrl
            }

            try {
                ensureCurrentFetch(requestId)
                val requestBuilder = Request.Builder()
                    .url(sourceUrl)
                    .header("Accept", "application/x-mpegurl, audio/x-mpegurl, text/plain, */*")
                if (forceNetwork) requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
                val request = requestBuilder.build()
                val content = executePlaylistRequest(client(appContext), request)

                ensureCurrentFetch(requestId)
                val groups = parsePlaylist(content)
                if (!PlaylistSnapshotPolicy.containsPlayableChannel(groups)) {
                    throw IOException("节目单为空或没有可播放频道")
                }
                ensureCurrentFetch(requestId)
                val now = System.currentTimeMillis()
                val snapshotWritten = synchronized(sourceStateLock) {
                    if (getSourceUrl(appContext) != sourceUrl) {
                        false
                    } else {
                        fetchGate.runIfCurrent(requestId) {
                            prefs.edit()
                                .putString(KEY_CACHED_SOURCE_URL, sourceUrl)
                                .putString(KEY_CACHED_PLAYLIST, content)
                                .putLong(KEY_CACHED_AT, now)
                                .apply()
                        }
                    }
                }
                if (!snapshotWritten) throw CancellationException("旧节目单请求已淘汰")

                TvChannelFetchResult(groups, sourceUrl, fromSnapshot = false, updatedAtMillis = now)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ensureCurrentFetch(requestId)
                val (snapshot, cachedSource, cachedAt) = synchronized(sourceStateLock) {
                    Triple(
                        prefs.getString(KEY_CACHED_PLAYLIST, null),
                        prefs.getString(KEY_CACHED_SOURCE_URL, null),
                        prefs.getLong(KEY_CACHED_AT, 0L)
                    )
                }
                val snapshotGroups = snapshot
                    ?.takeIf(String::isNotBlank)
                    ?.let { cachedPlaylist -> runCatching { parsePlaylist(cachedPlaylist) }.getOrNull() }
                if (snapshotGroups != null && PlaylistSnapshotPolicy.shouldUseSnapshot(
                        currentSourceUrl = sourceUrl,
                        snapshotSourceUrl = cachedSource,
                        snapshotGroups = snapshotGroups
                    )
                ) {
                    ensureCurrentFetch(requestId)
                    val sourceStillCurrent = synchronized(sourceStateLock) {
                        fetchGate.isCurrent(requestId) && getSourceUrl(appContext) == sourceUrl
                    }
                    if (!sourceStillCurrent) {
                        throw CancellationException("当前节目源已切换")
                    }
                    TvChannelFetchResult(
                        groups = snapshotGroups,
                        sourceUrl = sourceUrl,
                        fromSnapshot = true,
                        updatedAtMillis = cachedAt
                    )
                } else {
                    throw error
                }
            }
        }
    }

    private fun ensureCurrentFetch(requestId: Long) {
        if (!fetchGate.isCurrent(requestId)) {
            throw CancellationException("旧节目单请求已淘汰")
        }
    }

    private suspend fun executePlaylistRequest(
        client: OkHttpClient,
        request: Request
    ): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use { currentResponse ->
                        if (!currentResponse.isSuccessful) {
                            throw IOException("Unexpected code $currentResponse")
                        }
                        readPlaylist(currentResponse.body)
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun client(context: Context): OkHttpClient {
        httpClient?.let { return it }
        return synchronized(this) {
            httpClient ?: OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "playlist_http_cache"), HTTP_CACHE_BYTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
                .also { httpClient = it }
        }
    }

    private fun readPlaylist(body: ResponseBody): String {
        if (body.contentLength() > MAX_PLAYLIST_CHARS) {
            throw IOException("节目单文件过大")
        }

        return body.charStream().use { reader ->
            val buffer = CharArray(8 * 1024)
            val content = StringBuilder()
            var total = 0
            while (true) {
                val read = reader.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_PLAYLIST_CHARS) throw IOException("节目单文件过大")
                content.append(buffer, 0, read)
            }
            content.toString()
        }
    }

    internal fun parsePlaylist(content: String): Map<String, List<Movie>> {
        val channels = mutableListOf<Movie>()
        val normalizedContent = content.removePrefix("\uFEFF").trimStart()
        if (normalizedContent.startsWith("#EXTM3U", ignoreCase = true)) {
            parseM3U(normalizedContent, channels)
        } else {
            parseTxt(normalizedContent, channels)
        }
        return groupChannels(channels)
    }

    private fun parseTxt(content: String, channels: MutableList<Movie>) {
        var currentGroup: String? = null
        val identityOccurrences = mutableMapOf<String, Int>()
        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isNotBlank() && trimmedLine.contains(",")) {
                val parts = trimmedLine.split(",", limit = 2)
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val url = parts[1].trim()
                    if (url.equals("#genre#", ignoreCase = true)) {
                        currentGroup = name.takeIf { it.isNotBlank() }
                        return@forEach
                    }
                    if (name.isBlank() || url.isBlank()) return@forEach
                    val category = currentGroup?.trim()
                        .takeUnless { it.isNullOrBlank() || it == DEFAULT_GROUP }
                        ?: inferCategory(name)
                    val identity = channelIdentity(category, name)
                    val occurrence = identityOccurrences[identity] ?: 0
                    identityOccurrences[identity] = occurrence + 1
                    channels.add(
                        Movie(
                            id = stableChannelId(identity, occurrence),
                            title = name,
                            videoUrl = url,
                            studio = Movie.LIVE_STUDIO,
                            description = "正在播放: $name",
                            cardImageUrl = null,
                            backgroundImageUrl = null,
                            category = currentGroup
                        )
                    )
                }
            }
        }
    }

    private fun parseM3U(content: String, channels: MutableList<Movie>) {
        var currentName = ""
        var currentLogo: String? = null
        var currentTvgId: String? = null
        var currentGroup = DEFAULT_GROUP
        val identityOccurrences = mutableMapOf<String, Int>()

        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF:")) {
                // 解析频道名
                // 格式通常为 #EXTINF:-1 tvg-id="" tvg-name="" tvg-logo="" group-title="",Channel Name
                val namePart = trimmedLine.substringAfterLast(",")
                currentName = namePart.trim()

                // 解析 Logo (可选)
                val logoMatch = logoPattern.find(trimmedLine)
                currentLogo = logoMatch?.groupValues?.get(1)

                val tvgIdMatch = tvgIdPattern.find(trimmedLine)
                currentTvgId = tvgIdMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

                // 解析分组 (可选)
                val groupMatch = groupPattern.find(trimmedLine)
                currentGroup = groupMatch?.groupValues?.get(1)?.trim().orEmpty().ifBlank { DEFAULT_GROUP }
                
            } else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // 这一行是 URL
                if (currentName.isNotEmpty()) {
                    val category = currentGroup.trim()
                        .takeUnless { it.isBlank() || it == DEFAULT_GROUP }
                        ?: inferCategory(currentName)
                    val identity = currentTvgId?.let {
                        "tvg:${encodeIdentityPart(normalizeIdentityPart(it))}"
                    }
                        ?: channelIdentity(category, currentName)
                    val occurrence = identityOccurrences[identity] ?: 0
                    identityOccurrences[identity] = occurrence + 1
                    channels.add(
                        Movie(
                            id = stableChannelId(identity, occurrence),
                            title = currentName,
                            videoUrl = trimmedLine,
                            studio = Movie.LIVE_STUDIO,
                            description = "正在播放: $currentName",
                            cardImageUrl = currentLogo,
                            backgroundImageUrl = null,
                            category = currentGroup
                        )
                    )
                    // 重置，因为我们已经处理完一个频道
                    currentName = ""
                    currentLogo = null
                    currentTvgId = null
                }
            }
        }
    }

    private fun groupChannels(channels: List<Movie>): Map<String, List<Movie>> {
        val groups = linkedMapOf<String, MutableList<Movie>>()
        
        channels.forEach { channel ->
            val title = channel.title ?: ""
            val category = channel.category?.trim().takeUnless { it.isNullOrBlank() || it == DEFAULT_GROUP }
                ?: inferCategory(title)
            groups.getOrPut(category) { mutableListOf() }.add(channel)
        }

        val orderedGroups = linkedMapOf<String, MutableList<Movie>>()
        groups["央视频道"]?.let { channelsInGroup ->
            orderedGroups["央视频道"] = channelsInGroup
        }
        groups.forEach { (category, channelsInGroup) ->
            if (category != "央视频道") {
                orderedGroups[category] = channelsInGroup
            }
        }

        return orderedGroups.mapValues { (_, channelsInGroup) -> channelsInGroup.toList() }
    }

    private fun inferCategory(title: String): String {
        return when {
            title.contains("4K", ignoreCase = true) -> "4K超高清"
            title.contains("CCTV", ignoreCase = true) -> "央视频道"
            title.contains("卫视") -> "卫视综合"
            title.contains("BRTV", ignoreCase = true) || title.contains("北京") -> "北京频道"
            title.contains("IPTV", ignoreCase = true) -> "IPTV精选"
            title.contains("CGTN", ignoreCase = true) -> "国际频道"
            else -> DEFAULT_GROUP
        }
    }

    private fun channelIdentity(category: String, title: String): String {
        return "name:${encodeIdentityPart(normalizeIdentityPart(category))}" +
            encodeIdentityPart(normalizeIdentityPart(title))
    }

    private fun encodeIdentityPart(value: String): String {
        return "${value.length}:$value"
    }

    private fun normalizeIdentityPart(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }

    private fun stableChannelId(identity: String, occurrence: Int): Long {
        return stableId("channel-v2:$identity:$occurrence")
    }

    private fun stableId(value: String): Long {
        return UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).mostSignificantBits
    }
}
