package com.android.tv

import android.content.Context
import java.util.Locale

/** Persists user-pinned channels independently from their stream URLs. */
object ChannelFavorites {
    private const val PREFS_NAME = "channel_favorites"
    private const val KEY_FAVORITE_KEYS = "favorite_channel_keys"
    private const val CANONICAL_KEY_PREFIX = "channel-v2:"
    private val whitespace = Regex("\\s+")

    fun favoriteKeys(context: Context): Set<String> {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITE_KEYS, emptySet())
            .orEmpty()
            .toSet()
    }

    fun isFavorite(channel: Movie, favoriteKeys: Set<String>): Boolean {
        return favoriteKeyFor(channel)?.let(favoriteKeys::contains) == true
    }

    /**
     * Replaces the pre-v2 title-only keys with the current stable channel keys.
     * A duplicate legacy title is assigned to the first matching playlist entry
     * because the old format did not retain enough information to choose one.
     */
    fun migrateLegacyKeys(context: Context, channels: List<Movie>): Set<String> {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedKeys = preferences.getStringSet(KEY_FAVORITE_KEYS, emptySet()).orEmpty().toSet()
        val migratedKeys = migrateLegacyKeys(channels, savedKeys)
        if (migratedKeys != savedKeys) {
            preferences.edit().putStringSet(KEY_FAVORITE_KEYS, migratedKeys).apply()
        }
        return migratedKeys
    }

    internal fun migrateLegacyKeys(channels: List<Movie>, savedKeys: Set<String>): Set<String> {
        if (savedKeys.isEmpty()) return savedKeys

        val channelsByLegacyTitle = channels
            .asSequence()
            .filter { it.isLive && !it.videoUrl.isNullOrBlank() }
            .mapNotNull { channel ->
                legacyTitleKeyFor(channel)?.let { titleKey -> titleKey to channel }
            }
            .groupBy({ it.first }, { it.second })
        val migratedKeys = savedKeys.toMutableSet()

        savedKeys
            .filterNot(::isCanonicalKey)
            .forEach { legacyKey ->
                val candidates = channelsByLegacyTitle[legacyKey].orEmpty()
                if (candidates.isEmpty()) return@forEach

                val selected = candidates.firstOrNull { channel ->
                    favoriteKeyFor(channel)?.let(savedKeys::contains) == true
                } ?: candidates.first()
                val canonicalKey = favoriteKeyFor(selected) ?: return@forEach
                migratedKeys.remove(legacyKey)
                migratedKeys.add(canonicalKey)
            }

        return migratedKeys
    }

    /** Returns true when the channel was added, false when it was removed. */
    fun toggle(context: Context, channel: Movie): Boolean {
        val channelKey = favoriteKeyFor(channel) ?: return false
        val updatedKeys = favoriteKeys(context).toMutableSet()
        val added = updatedKeys.add(channelKey)
        if (!added) updatedKeys.remove(channelKey)

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAVORITE_KEYS, updatedKeys)
            .apply()
        return added
    }

    internal fun favoriteKeyFor(channel: Movie): String? {
        if (channel.id != 0L) return "$CANONICAL_KEY_PREFIX${channel.id}"
        return legacyTitleKeyFor(channel)?.let { "$CANONICAL_KEY_PREFIX$it" }
    }

    private fun legacyTitleKeyFor(channel: Movie): String? {
        return channel.title
            ?.trim()
            ?.replace(whitespace, " ")
            ?.takeIf(String::isNotEmpty)
            ?.lowercase(Locale.ROOT)
    }

    private fun isCanonicalKey(key: String): Boolean {
        return key.startsWith(CANONICAL_KEY_PREFIX)
    }
}

/** Keeps playlist order while resolving a user's saved favorites. */
internal object FavoriteChannelResolver {
    fun resolve(channels: List<Movie>, favoriteKeys: Set<String>): List<Movie> {
        if (favoriteKeys.isEmpty()) return emptyList()
        return channels.filter { channel ->
            channel.isLive &&
                !channel.videoUrl.isNullOrBlank() &&
                ChannelFavorites.isFavorite(channel, favoriteKeys)
        }
    }
}
