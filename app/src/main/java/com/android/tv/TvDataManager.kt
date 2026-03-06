package com.android.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object TvDataManager {
    private const val DEFAULT_TV_LIST_URL = "https://raw.githubusercontent.com/HaidyCao/configs/refs/heads/main/tvlist.txt"
    private val client = OkHttpClient()

    suspend fun fetchTvChannels(context: Context): Map<String, List<Movie>> {
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
            val sourceUrl = prefs.getString("tv_source_url", DEFAULT_TV_LIST_URL) ?: DEFAULT_TV_LIST_URL
            
            val channels = mutableListOf<Movie>()
            val request = Request.Builder().url(sourceUrl).build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    
                    val content = response.body?.string() ?: ""
                    
                    if (content.startsWith("#EXTM3U", ignoreCase = true)) {
                        parseM3U(content, channels)
                    } else {
                        parseTxt(content, channels)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 按类别对频道进行分组
            groupChannels(channels)
        }
    }

    private fun parseTxt(content: String, channels: MutableList<Movie>) {
        var idCounter = 1000L
        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isNotBlank() && trimmedLine.contains(",")) {
                val parts = trimmedLine.split(",")
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val url = parts[1].trim()
                    channels.add(
                        Movie(
                            id = idCounter++,
                            title = name,
                            videoUrl = url,
                            studio = "直播频道",
                            description = "正在播放: $name",
                            cardImageUrl = null,
                            backgroundImageUrl = null
                        )
                    )
                }
            }
        }
    }

    private fun parseM3U(content: String, channels: MutableList<Movie>) {
        var idCounter = 2000L
        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = "其他频道"

        content.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("#EXTINF:")) {
                // 解析频道名
                // 格式通常为 #EXTINF:-1 tvg-id="" tvg-name="" tvg-logo="" group-title="",Channel Name
                val namePart = trimmedLine.substringAfterLast(",")
                currentName = namePart.trim()

                // 解析 Logo (可选)
                val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(trimmedLine)
                currentLogo = logoMatch?.groupValues?.get(1)

                // 解析分组 (可选)
                val groupMatch = Regex("""group-title="([^"]+)"""").find(trimmedLine)
                currentGroup = groupMatch?.groupValues?.get(1) ?: "其他频道"
                
            } else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                // 这一行是 URL
                if (currentName.isNotEmpty()) {
                    channels.add(
                        Movie(
                            id = idCounter++,
                            title = currentName,
                            videoUrl = trimmedLine,
                            studio = "直播频道", // 使用 studio 作为标识
                            description = "正在播放: $currentName",
                            cardImageUrl = currentLogo,
                            backgroundImageUrl = null,
                            category = currentGroup // 我们加一个隐形成员或者复用 studio 逻辑，但这里为了兼容 groupChannels 逻辑
                        )
                    )
                    // 重置，因为我们已经处理完一个频道
                    currentName = ""
                    currentLogo = null
                }
            }
        }
    }

    private fun groupChannels(channels: List<Movie>): Map<String, List<Movie>> {
        val groups = mutableMapOf<String, MutableList<Movie>>()
        
        channels.forEach { channel ->
            val title = channel.title ?: ""
            // 如果是 M3U 且自带了分组，我们保留（逻辑上以后可以优化），这里先用原来的逻辑增强
            val category = when {
                // 如果有 M3U 定义的分类，且不属于下面这些通用分类，可以考虑使用它
                title.contains("CCTV", ignoreCase = true) -> "央视频道"
                title.contains("卫视") -> "卫视综合"
                title.contains("BRTV") || title.contains("北京") -> "北京频道"
                title.contains("IPTV") -> "IPTV精选"
                title.contains("CGTN") -> "国际频道"
                title.contains("4K") -> "4K超高清"
                else -> "其他频道"
            }
            groups.getOrPut(category) { mutableListOf() }.add(channel)
        }
        
        return groups
    }
}
