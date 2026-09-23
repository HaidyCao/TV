package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvSourceSwitchPolicyTest {

    @Test
    fun legacy_active_snapshot_is_read_without_inventing_a_previous_source() {
        val legacy = snapshot("https://legacy.example/list.m3u", 10L)
        val migrated = TvSourceStateMigrationPolicy.fromLegacy(legacy.sourceUrl, legacy)

        assertEquals(legacy.sourceUrl, migrated.activeSourceUrl)
        assertEquals(legacy, migrated.activeSnapshot)
        assertNull(migrated.previousSnapshot)
        assertNull(TvSourceSwitchPolicy.previousSnapshot(migrated))
    }

    @Test
    fun activation_keeps_the_last_valid_active_snapshot_and_restore_swaps_sources() {
        val legacy = snapshot("https://legacy.example/list.m3u", 10L)
        val current = TvSourceStateMigrationPolicy.fromLegacy(legacy.sourceUrl, legacy)
        val candidate = snapshot("https://candidate.example/list.m3u", 20L)

        val activated = TvSourceSwitchPolicy.activate(current, candidate)
        assertEquals(candidate.sourceUrl, activated.activeSourceUrl)
        assertEquals(candidate, activated.activeSnapshot)
        assertEquals(legacy, TvSourceSwitchPolicy.previousSnapshot(activated))

        val restored = TvSourceSwitchPolicy.restore(activated)
        assertEquals(legacy.sourceUrl, restored?.activeSourceUrl)
        assertEquals(legacy, restored?.activeSnapshot)
        assertEquals(candidate, restored?.previousSnapshot)
    }

    @Test
    fun failed_or_unusable_candidates_leave_the_entire_source_state_unchanged() {
        val active = snapshot("https://active.example/list.m3u", 10L)
        val previous = snapshot("https://previous.example/list.m3u", 5L)
        val state = TvSourceStoredState(active.sourceUrl, active, previous)

        assertEquals(state, TvSourceSwitchPolicy.activate(state, null))
        assertEquals(
            state,
            TvSourceSwitchPolicy.activate(
                state,
                snapshot("https://empty.example/list.m3u", 30L).copy(channelCount = 0)
            )
        )
    }

    @Test
    fun missing_or_mismatched_legacy_snapshot_never_becomes_a_fallback() {
        val activeUrl = "https://active.example/list.m3u"
        val missing = TvSourceStateMigrationPolicy.fromLegacy(activeUrl, null)
        val mismatched = TvSourceStateMigrationPolicy.fromLegacy(
            activeUrl,
            snapshot("https://other.example/list.m3u", 10L)
        )
        val newSource = snapshot("https://candidate.example/list.m3u", 20L)

        assertNull(TvSourceSwitchPolicy.previousSnapshot(missing))
        assertNull(TvSourceSwitchPolicy.previousSnapshot(mismatched))
        assertNull(TvSourceSwitchPolicy.previousSnapshot(TvSourceSwitchPolicy.activate(missing, newSource)))
        assertNull(TvSourceSwitchPolicy.restore(missing))
    }

    @Test
    fun activation_keeps_an_existing_usable_fallback_when_active_snapshot_is_missing() {
        val previous = snapshot("https://previous.example/list.m3u", 5L)
        val current = TvSourceStoredState(
            activeSourceUrl = "https://active.example/list.m3u",
            activeSnapshot = null,
            previousSnapshot = previous
        )
        val candidate = snapshot("https://candidate.example/list.m3u", 20L)

        val activated = TvSourceSwitchPolicy.activate(current, candidate)

        assertEquals(candidate, activated.activeSnapshot)
        assertEquals(previous, activated.previousSnapshot)
    }

    private fun snapshot(sourceUrl: String, updatedAtMillis: Long) = TvSourceSnapshot(
        sourceUrl = sourceUrl,
        playlist = "news,channel,http://stream.example/$updatedAtMillis",
        updatedAtMillis = updatedAtMillis,
        channelCount = 1,
        groupCount = 1
    )
}
