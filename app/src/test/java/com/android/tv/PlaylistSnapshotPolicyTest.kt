package com.android.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSnapshotPolicyTest {

    @Test
    fun same_source_usable_snapshot_is_accepted() {
        assertTrue(
            PlaylistSnapshotPolicy.shouldUseSnapshot(
                currentSourceUrl = "https://example.test/source.txt",
                snapshotSourceUrl = "https://example.test/source.txt",
                snapshotGroups = mapOf("新闻" to listOf(playableChannel()))
            )
        )
    }

    @Test
    fun snapshot_from_another_source_is_rejected() {
        assertFalse(
            PlaylistSnapshotPolicy.shouldUseSnapshot(
                currentSourceUrl = "https://example.test/new.txt",
                snapshotSourceUrl = "https://example.test/old.txt",
                snapshotGroups = mapOf("新闻" to listOf(playableChannel()))
            )
        )
    }

    @Test
    fun empty_or_unplayable_response_cannot_be_cached_or_used() {
        val emptyGroups = emptyMap<String, List<Movie>>()
        val unplayableGroups = mapOf(
            "新闻" to listOf(Movie(title = "无地址", studio = Movie.LIVE_STUDIO))
        )

        assertFalse(PlaylistSnapshotPolicy.containsPlayableChannel(emptyGroups))
        assertFalse(PlaylistSnapshotPolicy.containsPlayableChannel(unplayableGroups))
        assertFalse(
            PlaylistSnapshotPolicy.shouldUseSnapshot(
                currentSourceUrl = "https://example.test/source.txt",
                snapshotSourceUrl = "https://example.test/source.txt",
                snapshotGroups = unplayableGroups
            )
        )
    }

    private fun playableChannel(): Movie {
        return Movie(
            title = "频道",
            videoUrl = "https://example.test/live",
            studio = Movie.LIVE_STUDIO
        )
    }
}
