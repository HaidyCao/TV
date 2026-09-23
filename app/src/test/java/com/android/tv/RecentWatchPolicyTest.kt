package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentWatchPolicyTest {

    @Test
    fun records_only_when_the_current_tv_request_is_actually_playing() {
        assertTrue(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.PLAYING, true, true, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.BUFFERING, false, true, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.CONNECTING, false, true, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.ERROR, false, true, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.PLAYING, false, true, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.PLAYING, true, false, true, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.PLAYING, true, true, false, true
        ))
        assertFalse(RecentWatchPolicy.shouldRecord(
            PlaybackUiState.PLAYING, true, true, true, false
        ))
    }

    @Test
    fun recording_updates_the_same_source_channel_and_caps_history_at_twenty() {
        val channels = (1L..25L).map { liveChannel(it) }
        var records = emptyList<RecentWatchRecord>()
        channels.forEachIndexed { index, channel ->
            records = requireNotNull(
                RecentWatchPolicy.record(
                    existing = records,
                    sourceUrl = SOURCE_A,
                    groupsSourceUrl = SOURCE_A,
                    groups = mapOf("All" to channels),
                    playingChannel = channel,
                    watchedAtMillis = index + 1L
                )
            )
        }

        records = requireNotNull(
            RecentWatchPolicy.record(
                existing = records,
                sourceUrl = SOURCE_A,
                groupsSourceUrl = SOURCE_A,
                groups = mapOf("All" to channels),
                playingChannel = channels.first(),
                watchedAtMillis = 100L
            )
        )

        assertEquals(RecentWatchPolicy.MAX_RECORDS, records.size)
        assertEquals(1L, records.first().channelId)
        assertEquals(100L, records.first().watchedAtMillis)
        assertEquals(1, records.count { it.sourceUrl == SOURCE_A && it.channelId == 1L })
        assertEquals((7L..25L).toSet() + 1L, records.map(RecentWatchRecord::channelId).toSet())
    }

    @Test
    fun source_and_group_owner_must_match_before_history_can_be_recorded_or_resolved() {
        val channel = liveChannel(1)
        val groups = mapOf("All" to listOf(channel))
        val record = requireNotNull(RecentWatchPolicy.record(
            existing = emptyList(),
            sourceUrl = SOURCE_A,
            groupsSourceUrl = SOURCE_A,
            groups = groups,
            playingChannel = channel,
            watchedAtMillis = 10L
        )).single()

        assertEquals(null, RecentWatchPolicy.record(
            existing = emptyList(),
            sourceUrl = SOURCE_B,
            groupsSourceUrl = SOURCE_A,
            groups = groups,
            playingChannel = channel,
            watchedAtMillis = 11L
        ))
        assertTrue(RecentWatchPolicy.resolve(listOf(record), SOURCE_B, SOURCE_A, groups).isEmpty())
        assertTrue(RecentWatchPolicy.resolve(listOf(record), SOURCE_A, SOURCE_B, groups).isEmpty())
        assertEquals(listOf(channel), RecentWatchPolicy.resolve(
            listOf(record), SOURCE_A, SOURCE_A, groups
        ).map(ResolvedRecentWatch::channel))
    }

    @Test
    fun unique_channel_url_changes_resolve_to_the_current_playlist_movie() {
        val watched = liveChannel(7, url = "https://old.example/live")
        val record = recordFor(watched)
        val updated = watched.copy(videoUrl = "https://new.example/live")

        val resolved = RecentWatchPolicy.resolve(
            listOf(record), SOURCE_A, SOURCE_A, mapOf("All" to listOf(updated))
        )

        assertEquals(listOf("https://new.example/live"), resolved.map { it.channel.videoUrl })
    }

    @Test
    fun reordered_duplicate_titles_do_not_fall_back_to_a_different_channel_url() {
        val first = liveChannel(1, title = "同名频道", url = "https://a.example/live")
        val second = liveChannel(2, title = "同名频道", url = "https://b.example/live")
        val record = requireNotNull(RecentWatchPolicy.record(
            emptyList(), SOURCE_A, SOURCE_A, mapOf("All" to listOf(first, second)), first, 20L
        )).single()
        val reordered = listOf(
            first.copy(videoUrl = second.videoUrl),
            second.copy(videoUrl = first.videoUrl)
        )

        val resolved = RecentWatchPolicy.resolve(
            listOf(record), SOURCE_A, SOURCE_A, mapOf("All" to reordered)
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun repeated_m3u_identity_across_different_titles_is_still_checked_after_reordering() {
        val originalGroups = TvDataManager.parsePlaylist(
            """#EXTM3U
                |#EXTINF:-1 tvg-id="shared" group-title="新闻",频道甲
                |https://a.example/live
                |#EXTINF:-1 tvg-id="shared" group-title="体育",频道乙
                |https://b.example/live
            """.trimMargin()
        )
        val watched = originalGroups.getValue("新闻").single()
        val record = requireNotNull(RecentWatchPolicy.record(
            emptyList(), SOURCE_A, SOURCE_A, originalGroups, watched, 25L
        )).single()
        val reorderedGroups = TvDataManager.parsePlaylist(
            """#EXTM3U
                |#EXTINF:-1 tvg-id="shared" group-title="体育",频道乙
                |https://b.example/live
                |#EXTINF:-1 tvg-id="shared" group-title="新闻",频道甲
                |https://a.example/live
            """.trimMargin()
        )

        assertTrue(RecentWatchPolicy.resolve(
            listOf(record), SOURCE_A, SOURCE_A, reorderedGroups
        ).isEmpty())
    }

    private fun recordFor(channel: Movie): RecentWatchRecord = requireNotNull(
        RecentWatchPolicy.record(
            existing = emptyList(),
            sourceUrl = SOURCE_A,
            groupsSourceUrl = SOURCE_A,
            groups = mapOf("All" to listOf(channel)),
            playingChannel = channel,
            watchedAtMillis = 10L
        )
    ).single()

    private fun liveChannel(
        id: Long,
        title: String = "频道 $id",
        url: String = "https://example.test/live/$id"
    ) = Movie(
        id = id,
        title = title,
        category = "电视",
        videoUrl = url,
        studio = Movie.LIVE_STUDIO
    )

    companion object {
        private const val SOURCE_A = "https://list.example/a.m3u"
        private const val SOURCE_B = "https://list.example/b.m3u"
    }
}
