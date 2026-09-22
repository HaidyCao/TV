package com.android.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleLivePreviewQueueTest {

    @Test
    fun only_visible_unfocused_live_candidates_without_cache_are_eligible() {
        val visible = VisibleLivePreviewCandidate(
            requestKey = "1:https://example.test/news.m3u8",
            videoUrl = "https://example.test/news.m3u8",
            isLive = true,
            isVisible = true,
            isFocused = false
        )

        assertTrue(VisibleLivePreviewPolicy.canRequest(visible, cached = false))
        assertFalse(VisibleLivePreviewPolicy.canRequest(visible, cached = true))
        assertFalse(VisibleLivePreviewPolicy.canRequest(visible.copy(isFocused = true), false))
        assertFalse(VisibleLivePreviewPolicy.canRequest(visible.copy(isVisible = false), false))
        assertFalse(VisibleLivePreviewPolicy.canRequest(visible.copy(isLive = false), false))
        assertFalse(
            VisibleLivePreviewPolicy.canRequest(visible.copy(videoUrl = " "), cached = false)
        )
    }

    @Test
    fun queue_deduplicates_duplicate_row_occurrences_by_video_url() {
        val queue = VisibleLivePreviewRequestQueue()

        assertTrue(queue.enqueue("https://example.test/news.m3u8"))
        assertFalse(queue.enqueue("https://example.test/news.m3u8"))
        assertTrue(queue.enqueue("https://example.test/sports.m3u8"))

        assertEquals("https://example.test/news.m3u8", queue.poll()?.videoUrl)
        assertEquals("https://example.test/sports.m3u8", queue.poll()?.videoUrl)
        assertNull(queue.poll())
    }

    @Test
    fun removing_a_stale_url_keeps_other_visible_work() {
        val queue = VisibleLivePreviewRequestQueue()
        queue.enqueue("https://example.test/old.m3u8")
        queue.enqueue("https://example.test/current.m3u8")

        assertTrue(queue.remove("https://example.test/old.m3u8"))
        assertFalse(queue.contains("https://example.test/old.m3u8"))
        assertEquals("https://example.test/current.m3u8", queue.poll()?.videoUrl)
        assertEquals(0, queue.size)
    }
}
