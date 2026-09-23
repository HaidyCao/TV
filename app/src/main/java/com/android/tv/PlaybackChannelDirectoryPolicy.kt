package com.android.tv

/** The kinds of channel collections available from the TV playback directory. */
internal enum class PlaybackDirectoryCategoryKind {
    FAVORITES,
    RECENT,
    GROUP
}

internal data class PlaybackDirectoryCategory(
    val kind: PlaybackDirectoryCategoryKind,
    val key: String,
    val groupName: String? = null,
    val channels: List<Movie>
)

internal data class PlaybackChannelDirectory(
    val sourceUrl: String,
    val groupsSourceUrl: String?,
    val categories: List<PlaybackDirectoryCategory>,
    /** Category containing the current channel's original playlist group, when available. */
    val initialCategoryIndex: Int,
    val initialChannelIndex: Int,
    val currentChannelId: Long?,
    val currentChannelVideoUrl: String?,
    /** Snapshot URLs are immutable selection guards; they are never used to start playback. */
    val channelUrls: Map<Long, String>
)

internal sealed interface PlaybackDirectorySelection {
    data class Ready(val channel: Movie) : PlaybackDirectorySelection
    data object SourceChanged : PlaybackDirectorySelection
    data object ChannelChanged : PlaybackDirectorySelection
    data object Unavailable : PlaybackDirectorySelection
}

/** Builds a source-scoped directory and revalidates every selection against live repository data. */
internal object PlaybackChannelDirectoryPolicy {

    fun build(
        sourceUrl: String,
        groupsSourceUrl: String?,
        groups: Map<String, List<Movie>>,
        favoriteKeys: Set<String>,
        recentChannels: List<Movie>,
        currentChannel: Movie?,
        currentPlaybackSourceUrl: String?
    ): PlaybackChannelDirectory {
        if (sourceUrl.isBlank() || groupsSourceUrl != sourceUrl) {
            return PlaybackChannelDirectory(
                sourceUrl, groupsSourceUrl, emptyList(), 0, 0, null, null, emptyMap()
            )
        }

        val playable = groups.values.flatten().filter(::isPlayableLive)
        val uniqueById = playable.groupBy(Movie::id)
            .filterValues { candidates -> candidates.size == 1 }
            .mapValues { (_, candidates) -> candidates.single() }
        val currentCandidate = currentChannel?.takeIf { channel ->
            currentPlaybackSourceUrl == sourceUrl &&
                uniqueById[channel.id]?.videoUrl == channel.videoUrl
        }
        val currentChannelId = currentCandidate?.id
        val categories = mutableListOf<PlaybackDirectoryCategory>()

        val favorites = FavoriteChannelResolver.resolve(playable, favoriteKeys)
            .mapNotNull { uniqueById[it.id] }
            .distinctBy(Movie::id)
        if (favorites.isNotEmpty()) {
            categories += PlaybackDirectoryCategory(
                kind = PlaybackDirectoryCategoryKind.FAVORITES,
                key = "favorites",
                channels = favorites
            )
        }

        val recent = recentChannels.mapNotNull { recent ->
            uniqueById[recent.id]?.takeIf { current -> current.videoUrl == recent.videoUrl }
        }.distinctBy(Movie::id)
        if (recent.isNotEmpty()) {
            categories += PlaybackDirectoryCategory(
                kind = PlaybackDirectoryCategoryKind.RECENT,
                key = "recent",
                channels = recent
            )
        }

        groups.forEach { (groupName, channels) ->
            val groupChannels = channels
                .asSequence()
                .filter(::isPlayableLive)
                .mapNotNull { uniqueById[it.id] }
                .distinctBy(Movie::id)
                .toList()
            if (groupChannels.isNotEmpty()) {
                categories += PlaybackDirectoryCategory(
                    kind = PlaybackDirectoryCategoryKind.GROUP,
                    key = "group:$groupName",
                    groupName = groupName,
                    channels = groupChannels
                )
            }
        }

        val initialGroupIndex = categories.indexOfFirst { category ->
            category.kind == PlaybackDirectoryCategoryKind.GROUP &&
                category.channels.any { it.id == currentChannelId }
        }
        val initialCategoryIndex = if (initialGroupIndex >= 0) {
            initialGroupIndex
        } else {
            categories.indexOfFirst { category -> category.channels.any { it.id == currentChannelId } }
                .takeIf { it >= 0 } ?: 0
        }
        val selectedChannels = categories.getOrNull(initialCategoryIndex)?.channels.orEmpty()
        val initialChannelIndex = selectedChannels.indexOfFirst { it.id == currentChannelId }
            .takeIf { it >= 0 } ?: 0

        return PlaybackChannelDirectory(
            sourceUrl = sourceUrl,
            groupsSourceUrl = groupsSourceUrl,
            categories = categories,
            initialCategoryIndex = initialCategoryIndex,
            initialChannelIndex = initialChannelIndex,
            currentChannelId = currentChannelId,
            currentChannelVideoUrl = currentCandidate?.videoUrl,
            channelUrls = uniqueById.mapValues { (_, channel) -> channel.videoUrl.orEmpty() }
        )
    }

    fun validateSelection(
        directory: PlaybackChannelDirectory,
        selectedChannelId: Long,
        currentSourceUrl: String,
        currentGroupsSourceUrl: String?,
        currentGroups: Map<String, List<Movie>>
    ): PlaybackDirectorySelection {
        if (directory.sourceUrl != currentSourceUrl ||
            directory.groupsSourceUrl != currentGroupsSourceUrl ||
            currentGroupsSourceUrl != currentSourceUrl
        ) return PlaybackDirectorySelection.SourceChanged

        val snapshotUrl = directory.channelUrls[selectedChannelId]
            ?: return PlaybackDirectorySelection.Unavailable
        val matches = currentGroups.values.flatten().filter { channel ->
            channel.id == selectedChannelId && isPlayableLive(channel)
        }
        if (matches.size != 1) return PlaybackDirectorySelection.ChannelChanged
        val currentChannel = matches.single()
        if (currentChannel.videoUrl != snapshotUrl) return PlaybackDirectorySelection.ChannelChanged
        return PlaybackDirectorySelection.Ready(currentChannel)
    }

    private fun isPlayableLive(channel: Movie): Boolean =
        channel.isLive && !channel.videoUrl.isNullOrBlank()
}

internal enum class PlaybackDirectoryKeyRoute {
    OPEN_DIRECTORY,
    CLOSE_DIRECTORY,
    PREVIOUS_CHANNEL,
    NEXT_CHANNEL,
    TOGGLE_CHANNEL_INFO,
    NAVIGATE_DIRECTORY,
    EXIT_PLAYBACK,
    UNHANDLED
}

/** Keeps remote-key meanings explicit when the directory temporarily owns the DPAD. */
internal object PlaybackDirectoryKeyPolicy {
    private const val KEYCODE_DPAD_LEFT = 21
    private const val KEYCODE_DPAD_UP = 19
    private const val KEYCODE_DPAD_DOWN = 20
    private const val KEYCODE_DPAD_RIGHT = 22
    private const val KEYCODE_DPAD_CENTER = 23
    private const val KEYCODE_ENTER = 66
    private const val KEYCODE_BACK = 4
    private const val KEYCODE_MENU = 82

    fun route(keyCode: Int, directoryOpen: Boolean): PlaybackDirectoryKeyRoute {
        if (directoryOpen) {
            return when {
                keyCode == KEYCODE_MENU || keyCode == KEYCODE_BACK ->
                    PlaybackDirectoryKeyRoute.CLOSE_DIRECTORY
                keyCode == KEYCODE_DPAD_UP || keyCode == KEYCODE_DPAD_DOWN ||
                    keyCode == KEYCODE_DPAD_LEFT || keyCode == KEYCODE_DPAD_RIGHT ||
                    keyCode == KEYCODE_DPAD_CENTER || keyCode == KEYCODE_ENTER ||
                    PlaybackChannelKeyPolicy.directionFor(keyCode) != null ->
                    PlaybackDirectoryKeyRoute.NAVIGATE_DIRECTORY
                else -> PlaybackDirectoryKeyRoute.UNHANDLED
            }
        }

        return when {
            keyCode == KEYCODE_DPAD_LEFT || keyCode == KEYCODE_MENU ->
                PlaybackDirectoryKeyRoute.OPEN_DIRECTORY
            keyCode == KEYCODE_BACK -> PlaybackDirectoryKeyRoute.EXIT_PLAYBACK
            PlaybackChannelKeyPolicy.directionFor(keyCode) == PlaybackChannelKeyPolicy.PREVIOUS_CHANNEL ->
                PlaybackDirectoryKeyRoute.PREVIOUS_CHANNEL
            PlaybackChannelKeyPolicy.directionFor(keyCode) == PlaybackChannelKeyPolicy.NEXT_CHANNEL ->
                PlaybackDirectoryKeyRoute.NEXT_CHANNEL
            keyCode == KEYCODE_DPAD_CENTER || keyCode == KEYCODE_ENTER ->
                PlaybackDirectoryKeyRoute.TOGGLE_CHANNEL_INFO
            else -> PlaybackDirectoryKeyRoute.UNHANDLED
        }
    }
}
