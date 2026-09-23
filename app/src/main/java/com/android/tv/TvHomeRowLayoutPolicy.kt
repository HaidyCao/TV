package com.android.tv

/** Maps semantic home rows to Leanback row indexes after optional rows are inserted. */
internal object TvHomeRowLayoutPolicy {
    fun hasFavorites(groups: Map<String, List<Movie>>, favoriteKeys: Set<String>): Boolean =
        FavoriteChannelResolver.resolve(groups.values.flatten(), favoriteKeys).isNotEmpty()

    fun favoritesRowIndex(): Int = 0

    /** Home recent history is inserted immediately after the first original group. */
    fun recentRowIndex(hasFavorites: Boolean): Int = (if (hasFavorites) 1 else 0) + 1

    fun originalGroupRowIndex(
        originalGroupIndex: Int,
        hasFavorites: Boolean,
        hasRecentRow: Boolean
    ): Int = originalGroupIndex + (if (hasFavorites) 1 else 0) +
        (if (hasRecentRow && originalGroupIndex >= 1) 1 else 0)
}
