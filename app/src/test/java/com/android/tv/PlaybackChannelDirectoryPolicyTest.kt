package com.android.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChannelDirectoryPolicyTest {

    @Test
    fun directory_requires_the_loaded_groups_to_belong_to_the_active_source() {
        val channel = liveChannel(1, "https://a.example/one.m3u8")

        val directory = PlaybackChannelDirectoryPolicy.build(
            sourceUrl = SOURCE_B,
            groupsSourceUrl = SOURCE_A,
            groups = mapOf("News" to listOf(channel)),
            favoriteKeys = emptySet(),
            recentChannels = emptyList(),
            currentChannel = channel,
            currentPlaybackSourceUrl = SOURCE_A
        )

        assertTrue(directory.categories.isEmpty())
        assertEquals(0, directory.initialCategoryIndex)
        assertEquals(emptyMap<Long, String>(), directory.channelUrls)
    }

    @Test
    fun categories_prioritize_favorites_and_recent_but_default_to_current_original_group() {
        val first = liveChannel(1, "https://a.example/one.m3u8", "First")
        val current = liveChannel(2, "https://a.example/two.m3u8", "Current")
        val other = liveChannel(3, "https://a.example/three.m3u8", "Other")
        val groups = linkedMapOf("News" to listOf(first, current), "Sports" to listOf(other))

        val directory = PlaybackChannelDirectoryPolicy.build(
            sourceUrl = SOURCE_A,
            groupsSourceUrl = SOURCE_A,
            groups = groups,
            favoriteKeys = setOf(ChannelFavorites.favoriteKeyFor(first)!!),
            recentChannels = listOf(current),
            currentChannel = current,
            currentPlaybackSourceUrl = SOURCE_A
        )

        assertEquals(
            listOf(
                PlaybackDirectoryCategoryKind.FAVORITES,
                PlaybackDirectoryCategoryKind.RECENT,
                PlaybackDirectoryCategoryKind.GROUP,
                PlaybackDirectoryCategoryKind.GROUP
            ),
            directory.categories.map(PlaybackDirectoryCategory::kind)
        )
        assertEquals(2, directory.initialCategoryIndex)
        assertEquals(1, directory.initialChannelIndex)
        assertEquals(current.id, directory.currentChannelId)
        assertEquals(current.videoUrl, directory.currentChannelVideoUrl)
    }

    @Test
    fun non_live_or_addressless_entries_do_not_create_categories() {
        val vod = Movie(id = 1, title = "VOD", videoUrl = "https://a.example/vod.mp4")
        val broken = liveChannel(2, "")

        val directory = PlaybackChannelDirectoryPolicy.build(
            sourceUrl = SOURCE_A,
            groupsSourceUrl = SOURCE_A,
            groups = mapOf("Unavailable" to listOf(vod, broken)),
            favoriteKeys = setOf(ChannelFavorites.favoriteKeyFor(vod)!!),
            recentChannels = listOf(vod),
            currentChannel = null,
            currentPlaybackSourceUrl = null
        )

        assertTrue(directory.categories.isEmpty())
    }

    @Test
    fun confirmation_returns_the_current_playlist_object_after_revalidation() {
        val snapshot = liveChannel(7, "https://a.example/seven.m3u8", "Old title")
        val directory = buildDirectory(snapshot)
        val currentPlaylistObject = liveChannel(7, snapshot.videoUrl!!, "Updated title")

        val result = PlaybackChannelDirectoryPolicy.validateSelection(
            directory = directory,
            selectedChannelId = snapshot.id,
            currentSourceUrl = SOURCE_A,
            currentGroupsSourceUrl = SOURCE_A,
            currentGroups = mapOf("News" to listOf(currentPlaylistObject))
        )

        assertSame(currentPlaylistObject, (result as PlaybackDirectorySelection.Ready).channel)
    }

    @Test
    fun selection_rejects_changed_source_address_and_ambiguous_identity() {
        val snapshot = liveChannel(9, "https://a.example/nine.m3u8")
        val directory = buildDirectory(snapshot)

        val changedSource = PlaybackChannelDirectoryPolicy.validateSelection(
            directory, snapshot.id, SOURCE_B, SOURCE_B, mapOf("News" to listOf(snapshot))
        )
        val changedAddress = PlaybackChannelDirectoryPolicy.validateSelection(
            directory,
            snapshot.id,
            SOURCE_A,
            SOURCE_A,
            mapOf("News" to listOf(liveChannel(snapshot.id, "https://a.example/replaced.m3u8")))
        )
        val ambiguousIdentity = PlaybackChannelDirectoryPolicy.validateSelection(
            directory,
            snapshot.id,
            SOURCE_A,
            SOURCE_A,
            mapOf(
                "News" to listOf(
                    snapshot,
                    liveChannel(snapshot.id, "https://a.example/duplicate.m3u8")
                )
            )
        )

        assertEquals(PlaybackDirectorySelection.SourceChanged, changedSource)
        assertEquals(PlaybackDirectorySelection.ChannelChanged, changedAddress)
        assertEquals(PlaybackDirectorySelection.ChannelChanged, ambiguousIdentity)
    }

    @Test
    fun remote_keys_switch_meaning_while_directory_is_open() {
        assertEquals(
            PlaybackDirectoryKeyRoute.OPEN_DIRECTORY,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_DPAD_LEFT, directoryOpen = false)
        )
        assertEquals(
            PlaybackDirectoryKeyRoute.OPEN_DIRECTORY,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_MENU, directoryOpen = false)
        )
        assertEquals(
            PlaybackDirectoryKeyRoute.PREVIOUS_CHANNEL,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_DPAD_UP, directoryOpen = false)
        )
        assertEquals(
            PlaybackDirectoryKeyRoute.NEXT_CHANNEL,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_DPAD_DOWN, directoryOpen = false)
        )
        assertEquals(
            PlaybackDirectoryKeyRoute.TOGGLE_CHANNEL_INFO,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_DPAD_CENTER, directoryOpen = false)
        )
        assertEquals(
            PlaybackDirectoryKeyRoute.EXIT_PLAYBACK,
            PlaybackDirectoryKeyPolicy.route(KeyEvent.KEYCODE_BACK, directoryOpen = false)
        )
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        ).forEach { keyCode ->
            assertEquals(
                PlaybackDirectoryKeyRoute.NAVIGATE_DIRECTORY,
                PlaybackDirectoryKeyPolicy.route(keyCode, directoryOpen = true)
            )
        }
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU).forEach { keyCode ->
            assertEquals(
                PlaybackDirectoryKeyRoute.CLOSE_DIRECTORY,
                PlaybackDirectoryKeyPolicy.route(keyCode, directoryOpen = true)
            )
        }
    }

    private fun buildDirectory(channel: Movie) = PlaybackChannelDirectoryPolicy.build(
        sourceUrl = SOURCE_A,
        groupsSourceUrl = SOURCE_A,
        groups = mapOf("News" to listOf(channel)),
        favoriteKeys = emptySet(),
        recentChannels = emptyList(),
        currentChannel = null,
        currentPlaybackSourceUrl = null
    )

    private fun liveChannel(id: Long, url: String, title: String = "Channel $id") = Movie(
        id = id,
        title = title,
        videoUrl = url,
        studio = Movie.LIVE_STUDIO
    )

    private companion object {
        const val SOURCE_A = "https://playlist-a.example/channels.txt"
        const val SOURCE_B = "https://playlist-b.example/channels.txt"
    }
}
