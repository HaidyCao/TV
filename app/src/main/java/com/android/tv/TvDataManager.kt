package com.android.tv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
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

object TvDataManager {
    const val DEFAULT_TV_LIST_URL = "https://raw.githubusercontent.com/HaidyCao/configs/refs/heads/main/tvlist.txt"

    private const val PREFS_NAME = "tv_prefs"
    private const val KEY_SOURCE_URL = "tv_source_url"
    private const val KEY_CACHED_SOURCE_URL = "cached_tv_source_url"
    private const val KEY_CACHED_PLAYLIST = "cached_tv_playlist"
    private const val KEY_CACHED_AT = "cached_tv_playlist_at"
    private const val MAX_PLAYLIST_CHARS = 512 * 1024
    private const val HTTP_CACHE_BYTES = 5L * 1024L * 1024L
    private const val DEFAULT_GROUP = "其他频道"

    private val logoPattern = Regex("""tvg-logo="([^"]+)""", RegexOption.IGNORE_CASE)
    private val tvgIdPattern = Regex("""tvg-id="([^"]*)""", RegexOption.IGNORE_CASE)
    private val groupPattern = Regex("""group-title="([^"]+)""", RegexOption.IGNORE_CASE)

    @Volatile
    private var httpClient: OkHttpClient? = null

    fun getSourceUrl(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_URL, DEFAULT_TV_LIST_URL)
            ?.trim()
            .orEmpty()
            .ifBlank { DEFAULT_TV_LIST_URL }
    }

    fun saveSourceUrl(context: Context, sourceUrl: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_URL, sourceUrl.trim())
            .apply()
    }

    fun isValidSourceUrl(sourceUrl: String): Boolean {
        val uri = runCatching { Uri.parse(sourceUrl.trim()) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    suspend fun fetchTvChannels(
        context: Context,
        forceNetwork: Boolean = false
    ): TvChannelFetchResult {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sourceUrl = getSourceUrl(appContext)
            require(isValidSourceUrl(sourceUrl)) { "节目源地址必须是有效的 HTTP 或 HTTPS URL" }

            try {
                val requestBuilder = Request.Builder()
                    .url(sourceUrl)
                    .header("Accept", "application/x-mpegurl, audio/x-mpegurl, text/plain, */*")
                if (forceNetwork) requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
                val request = requestBuilder.build()
                val content = client(appContext).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    readPlaylist(response.body)
                }

                val groups = parsePlaylist(content)
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putString(KEY_CACHED_SOURCE_URL, sourceUrl)
                    .putString(KEY_CACHED_PLAYLIST, content)
                    .putLong(KEY_CACHED_AT, now)
                    .apply()

                TvChannelFetchResult(groups, sourceUrl, fromSnapshot = false, updatedAtMillis = now)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val snapshot = prefs.getString(KEY_CACHED_PLAYLIST, null)
                val cachedSource = prefs.getString(KEY_CACHED_SOURCE_URL, null)
                if (!snapshot.isNullOrBlank() && cachedSource == sourceUrl) {
                    TvChannelFetchResult(
                        groups = parsePlaylist(snapshot),
                        sourceUrl = sourceUrl,
                        fromSnapshot = true,
                        updatedAtMillis = prefs.getLong(KEY_CACHED_AT, 0L)
                    )
                } else {
                    throw error
                }
            }
        }
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
        
        return groups.mapValues { (_, channelsInGroup) -> channelsInGroup.toList() }
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
